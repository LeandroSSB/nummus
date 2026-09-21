# M12 — Conciliation Automation

**Date:** 2026-09-21
**Status:** approved design, pending implementation plan

## Goal

Close the last open M6-era product thread: settlement-report re-ingest becomes scheduled and self-healing, and every OPEN (divergent) report pushes a digest event to operator-owned webhook endpoints — reusing the transactional outbox and the full M5/M10/M11 hardening rather than building a parallel delivery stack.

## Product decisions (locked)

1. **Operator webhooks ride the NULL-merchant namespace.** Operator endpoints are rows in `webhooks.webhook_endpoint` with `merchant_public_id = NULL` — the same namespace trick `idempotency_keys` has used since M7. One table, one delivery worker, one retry policy, one URL policy, one retention job; merchant-scoped queries never see operator rows and vice versa.
2. **The scheduler is tumbling with persisted state.** A single row (`conciliation.ingest_state`) holds `last_window_end`; each tick ingests one window and advances the marker only on success. The window **start is self-healing**: `greatest(state.last_window_end, max(summary.to))` — a manual operator ingest that covered pending territory is never re-covered (no duplicate coverage, no second digest event for the same window).
3. **Window end holds back a lag buffer** (`nummus.conciliation.window-lag`, default `PT30S`) — the M6 skew lesson: `settled_at` (JVM clock) and the simulator's `updated_at` (DB clock) can straddle a boundary by ~5s, and under tumbling windows a straddle would otherwise create a permanent spurious MISSING pair that no re-ingest heals.
4. **Alerting is a report digest, emitted on any OPEN ingest** — scheduled or manual, one code path. One event per OPEN report: counts and ids only, no amounts, no per-line push.
5. **Empty windows write no report:** a window with zero lines on both sides persists nothing but still advances the marker — a quiet system generates no report spam and the windows stay tight.

## Operator webhook endpoints

**Migration — `V15__operator_webhooks_and_conciliation_state.sql`** (also creates the scheduler state below):

```sql
alter table webhooks.webhook_endpoint
  alter column merchant_public_id drop not null,
  alter column merchant_public_id drop default;

create table conciliation.ingest_state (
  id              int primary key check (id = 1),
  last_window_end timestamptz not null,
  updated_at      timestamptz not null default now()
);

insert into conciliation.ingest_state (id, last_window_end) values (1, now());

grant select, insert, update on conciliation.ingest_state to nummus_app;
```

The seed default on `merchant_public_id` was migration-era backfill (V10); both registration paths now pass explicit values — merchant id or NULL — so the default goes away. Existing rows are untouched.

**REST (operator-authenticated; operators are role-level, so any operator key manages the shared operator endpoint set):**

- `POST /v1/operator/webhook-endpoints` — `{url, eventTypes}`; `WebhookUrlPolicy` enforced at registration (400 problem+json naming the violated rule); response carries the endpoint and its **secret exactly once** (same show-once mechanics as merchant endpoints and API keys).
- `GET /v1/operator/webhook-endpoints` — list, prefix only.
- `DELETE /v1/operator/webhook-endpoints/{id}` — soft delete (`status = 'DELETED'`); delivery history survives.
- **M10 parity for the operator namespace:** `GET /v1/operator/webhook-endpoints/{id}/deliveries?status=&after=&limit=` (array body, `Next-Cursor` response header when more pages exist, limit bounds 1–100, unknown cursor → empty page) and `POST /v1/operator/webhook-deliveries/{id}/redrive` (`@Idempotent`, 202, fresh retry cycle — mirroring the shipped merchant route shape).

Registration, listing, and delete flow through the existing `WebhookEndpointsService` with a NULL-merchant variant — one policy, one store, one worker; no parallel service.

## Outbox generalization and the divergence event

**Audience at fan-out — and a leak this closes.** Recon found that today's fan-out is **unscoped**: `insertEvent` cross-joins every ACTIVE endpoint, so a `payment_intent.settled` event for one merchant creates deliveries to every other merchant's matching endpoints — a latent cross-tenant delivery leak from the single-merchant M5 era that M7's scoping never revisited (registration and listing are scoped; delivery is not). M12 fixes it as part of generalizing the audience:

- merchant-audience events → ACTIVE endpoints `where merchant_public_id = <the event's merchant>`;
- operator-audience events → ACTIVE endpoints `where merchant_public_id is null`.

One mechanism serves both: the audience is a nullable merchant id, matched with `merchant_public_id is not distinct from :audience`. `IntentLifecycleEvent` gains the merchant id (every publish site in `PaymentsServiceImpl` already has it in scope), and the cross-tenant fix is pinned by test: two merchants' endpoints, one settles — only the owner's endpoint receives a delivery row. Both audiences honor the endpoint's `event_types` filter. The delivery worker, signature scheme (`t=` timestamp, per-endpoint secret), bounded retries with backoff, delivery-time URL revalidation, no-redirect rule, retention pruning, and cursor pagination apply to both namespaces unchanged — the worker resolves endpoints through the delivery row, so it needs no audience awareness.

**The event.** Type `conciliation.report_open`, emitted by `ConciliationService.ingest` inside its transaction whenever the summary lands `OPEN` (any trigger — the manual operator POST emits too; one code path). A `CONCILED` ingest emits nothing.

```json
{
  "type": "conciliation.report_open",
  "reportId": "<uuid>",
  "window": { "from": "…", "to": "…" },
  "matched": 41,
  "amountMismatched": 2,
  "missingInternal": 0,
  "missingExternal": 1
}
```

**Module boundary.** `conciliation` never touches `webhooks` tables. It calls the existing webhooks internal publish port — the same seam `payments` uses — which gains an operator-audience variant. Report rows and the event commit atomically: no OPEN report exists without its event, and vice versa.

## Scheduler

`conciliation.application.ConciliationWorker` — the same single-process `fixedDelay` discipline as the delivery and retention workers. Each tick is one transaction:

1. **Start:** `greatest(state.last_window_end, (select max(period_to) from conciliation.settlement_report))` — self-healing against manual ingests that covered pending territory: they are never re-covered, so no duplicate coverage and no second digest for the same window.
2. **End:** `now() - window-lag` (the DB clock, via SQL), so the boundary sits outside the M6 skew zone. If `end <= start` the tick is a no-op.
3. **Fetch and match** through the same fetch-and-match the manual path uses — one code path for manual and scheduled ingestion.
4. **Empty window (zero lines both sides):** no report row is written; the marker still advances — nothing was missed, the windows stay tight.
5. **Success:** report (+ digest event if OPEN) and `state.last_window_end = end` commit together. **Failure:** everything rolls back; the same window retries next tick, warn-logged.

**Config** (`nummus.conciliation.*`): `poll-delay-ms` (default `300000`), `initial-delay-ms` (default `60000`), `window-lag` (default `PT30S`). Test contexts pin the two delays to 3600000 in `IntegrationTestBase`, exactly as the webhook workers are pinned — no test context ever runs a scheduled ingest.

## Error handling

- Endpoint registration policy violation → 400 problem+json naming the rule (existing `UnsafeWebhookUrlException` path).
- Unknown or foreign endpoint/delivery → 404, deliberately indistinguishable (existing vocabulary).
- Scheduled tick failure → warn log, transaction rolled back, retried next tick; manual ingest stays available regardless.
- Manual overlapping ingests keep today's `DuplicateSettlementLinesException` → 400 behavior.

## Security and invariants

- The NULL-merchant namespace is invisible to every merchant-scoped query and vice versa — pinned by test in both directions.
- Operator endpoints face the same two-layer SSRF enforcement: registration 400 plus delivery-time revalidation; redirects never followed.
- Operator routes require operator keys (401/403 per the M8 gating); merchant keys never reach them.
- The digest carries counts and ids only — no amounts, no PII.
- Report + event + state advance are atomic (single transaction on each path).

## Testing

1. **Schema/roles:** `merchant_public_id` nullable, no default; `ingest_state` single-row constrained and seeded; `nummus_app` holds the new insert/update on `ingest_state` and existing endpoint grants cover NULL-merchant rows.
2. **Operator endpoint REST:** register (policy-400 on a private-IP URL, 201 with secret once, secret never repeated in list), soft delete; deliveries paginate with `Next-Cursor`; redrive 202 with a fresh retry cycle; **cross-namespace isolation** both directions (merchant sees nothing operator, operator listings see nothing merchant).
3. **Publishing:** OPEN ingest via the manual POST → exactly one event + one delivery per ACTIVE operator endpoint honoring `event_types`; scheduled OPEN ingest → same; CONCILED ingest → no event; the event rows exist only alongside the committed report (transactional). **Cross-tenant delivery isolation:** two merchants' endpoints, one settles — only the owner's endpoint receives a delivery row (the leak fix).
4. **Scheduler (worker method invoked directly; delays pinned):** first tick starts from the seeded state; empty window → no report and the marker advances; success writes report + event + advance together; a manual ingest overlapping pending territory followed by a tick → the window starts after the manual report's `period_to` (no re-coverage); window end respects the lag; a failing fetch rolls back report and state together.

Baseline at plan time: 297 tests, all green.

## Non-goals / deliberate bounds

- **Single-process scheduler and delivery worker** — scale-out needs the `SKIP LOCKED` treatment already documented for delivery and retention (M5/M10).
- **Digest-only alerting** — per-line divergence detail stays behind the report GET; no per-line push.
- **The operator endpoint set is role-level shared** — no per-operator attribution of endpoint ownership (the M8 bound carries until audit attribution lands).
- **No alert routing beyond `event_types`** — severity thresholds, per-verdict filters, and acknowledgment states are out of scope.
- **The lag is a property, not an SLA** — 30s trades alert latency for skew safety; a real PSP adapter still owes the settlement-timestamp contract M6 named.
