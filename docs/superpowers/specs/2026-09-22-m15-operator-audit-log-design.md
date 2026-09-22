# M15 — Operator Audit Log

**Date:** 2026-09-22
**Status:** approved design, pending implementation plan

## Goal

Complete the attribution story M14 began: every operator state-changing write — except fee changes, which already carry a richer typed trail — lands in one append-only, operator-readable audit log. Who did what, when, to what, in the same transaction as the action itself.

## Product decisions (locked)

1. **Scope: all operator state-changing writes except fees.** Merchant creation, operator key bootstrap/mint/rotate/revoke, operator webhook endpoint register/delete, operator delivery redrive, and manual conciliation ingest. Fee changes keep their M14 typed history (double-recording is noise); merchant self-serve actions stay out (attributed by tenancy); scheduled conciliation ticks log nothing (the machine is not an operator).
2. **Shape: typed core + jsonb detail.** One table with `actor_key` FK, `action` text, typed `subject_type`/`subject_id` for filtering, and a `detail` jsonb for action-specific extras. New actions need no migration; the common queries stay typed.
3. **The log is a seventh module, `audit`.** It owns one table and one port; it imports nothing. Callers (merchants, webhooks, conciliation) import `audit.application.OperatorAudit` — no new cross-module edges between feature modules.
4. **Recording is transactional with the action** (`MANDATORY` propagation, the digest-adapter discipline): an audit entry commits with the action or not at all. Where an operator write has no service transaction today (delivery redrive), one is introduced.
5. **No retroactive backfill.** The log starts empty at M15 — pre-M15 history stays genuinely absent, the same honesty as M14's `system` sentinel.

## The audit module

**Port** (`audit.application.OperatorAudit`):

```java
public interface OperatorAudit {
  /** Records one operator action inside the action's transaction (MANDATORY):
   *  an audit entry commits with the action or not at all. */
  void record(UUID actorKey, String action, String subjectType, UUID subjectId,
      Map<String, ?> detail);
}
```

The implementation lives in `audit.infrastructure`: it serializes `detail` with the shared `ObjectMapper` (empty map → `{}`) and inserts one row. The listing route below lives in `audit.interfaces` — the module owns its read surface and imports only `interfaces.auth` for gating, exactly like every other module's controllers.

**Migration — `V17__operator_audit_log.sql`:**

```sql
create schema audit;

create table audit.operator_action (
  id           bigint generated always as identity primary key,
  public_id    uuid not null default gen_random_uuid() unique,
  actor_key    uuid not null references merchants.operator_key(public_id),
  action       text not null,
  subject_type text not null,
  subject_id   uuid,
  detail       jsonb not null default '{}'::jsonb,
  occurred_at  timestamptz not null default now()
);
create index operator_action_action_idx on audit.operator_action (action, id desc);

grant usage on schema audit to nummus_app;
grant select, insert on audit.operator_action to nummus_app;
```

Append-only by grants — no update or delete, like fee history and conciliation reports.

## Call sites

| Action | Site | Subject | `detail` |
| --- | --- | --- | --- |
| `merchant.created` | merchant creation (actor threaded into the service) | the merchant | name |
| `operator_key.bootstrapped` | bootstrap (actor = the new key itself) | the new key | label |
| `operator_key.minted` | operator key mint | the new key | label |
| `operator_key.rotated` | operator rotate | the new key | retired key id, label |
| `operator_key.revoked` | operator revoke | the revoked key | — |
| `operator_endpoint.registered` | operator endpoint register | the endpoint | url |
| `operator_endpoint.deleted` | operator endpoint delete | the endpoint | — |
| `delivery.redriven` | operator redrive (gains a transactional wrapper) | the delivery | — |
| `conciliation.ingested` | manual ingest only | the report | window from/to |

Actor threading follows M14's fee pattern: the controller passes `operator.keyPublicId()` into the service; key lifecycle services record with the CALLING key as actor (the new key is the subject). The scheduled conciliation worker shares the ingest service but skips recording — the manual route passes an actor, the worker passes none (a `record`-vs-`skip` seam at the service boundary, not inside the audit module).

## Listing

`GET /v1/operator/audit-log?action=&after=&limit=` — operator-authenticated (403 for merchant keys, the M8 vocabulary), newest-first, the M10 pagination contract verbatim: array body, `Next-Cursor` response header only when more pages exist, `limit` 1–100 (outside → 400), unknown cursor → empty page without a header. `action` is an exact-match filter. Each row: `entryId`, `action`, `subjectType`, `subjectId`, `detail` (object), `occurredAt`, `actorKey`, `actorLabel` (joined at read; labels are immutable so no snapshot is needed).

## Error handling

- No new error vocabulary: the listing's `limit` rides the existing 400 mapping; gating is the standard 403/401 pair; unknown cursor is silent (M10 semantics).
- A recording failure fails the action (same transaction) — availability of the write yields to the audit guarantee, deliberately the opposite of `last_used_at`'s best-effort stance: attribution is the point of this milestone.

## Security and invariants

- Append-only: `nummus_app` holds no update or delete on `audit.operator_action`.
- Every operator sees the entire log (role-level stance — no actor filter, no scoping).
- `detail` is advisory display data, never a source of truth.
- Merchant-facing surfaces expose nothing from the audit module.

## Testing

1. **Schema/roles:** the table, the FK, the `(action, id desc)` index; select+insert only (update and delete both denied under `nummus_app`).
2. **Per-action recording:** one test per call site — exactly one entry, correct actor (the CALLING key), action string, subject id, and the `detail` spot checks (label on mint, url on registration, window bounds on ingest, retired key on rotation).
3. **Negatives:** a fee PUT records nothing; merchant self-serve key mint/rotate records nothing; a scheduled conciliation tick records nothing; bootstrap records with the new key as actor.
4. **Listing:** pagination walk with `Next-Cursor`; the `action` filter narrows correctly; unknown cursor → empty page; limit 0/101 → 400; merchant keys get 403; `actorLabel` joins and renders.
5. **Atomicity:** a forced action failure (the M13 `check (false)` technique on `audit.operator_action`) rolls the action AND its entry back together.

Baseline at plan time: 343 tests, all green.

## Non-goals / deliberate bounds

- **No retroactive backfill** — the log begins at M15; pre-M15 history is absent, not synthesized.
- **No actor filter or per-operator scoping** — role-level; RBAC remains future work on the M14 seam.
- **Fee changes are not double-recorded** — the M14 typed history is their audit trail.
- **`detail` has no schema contract** — consumers render it; nothing parses it as truth.
- **No audit of reads** — listings and GETs are out; only state-changing writes record.
