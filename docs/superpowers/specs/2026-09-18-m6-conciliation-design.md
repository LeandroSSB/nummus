# M6 — Conciliation reports design

**Date:** 2026-09-18 · **Status:** approved in brainstorming
**Builds on:** M1 ledger core, M2 accounts + REST, M3 payment intents + PSP simulator, M4 idempotency, M5 webhooks outbox

## Goal

Reconcile what the external payment network says it settled against what nummus
recorded: ingest a settlement report from the PSP simulator for a time window,
match every line against internal settled intents, and persist an immutable
report with per-line match status and a divergence summary — the audit
artifact a payments core owes its operators.

## Decisions (locked in brainstorming)

1. **The simulator emits the report.** The PSP simulator (the external
   network) gains `settlementReport(from, to)` — SUCCEEDED charges with their
   settlement timestamp in the window — plus an operator REST endpoint
   (`GET /simulator/settlement-report?from&to`). The `conciliation` module
   consumes it through its own `SettlementReportSource` port implemented by a
   simulator adapter: the PaymentNetwork inversion again. Rejected: manual
   report upload via REST (the external boundary would originate inside the
   app, unfaithful to the problem); both (double ingestion surface, no
   additional proof).
2. **Four match states.** Per report line (keyed by `chargePublicId`):
   `MATCHED` (internal settled intent, equal amount), `AMOUNT_MISMATCH`
   (settled, different amount), `MISSING_INTERNAL` (no intent, or intent not
   SETTLED — the network settled something we never recorded as settled), and
   `MISSING_EXTERNAL` for internal settlements in the window absent from the
   report (emitted as lines of origin `INTERNAL`). Rejected: two states with
   a free-text reason — unauditable, unqueryable by divergence type.
3. **No fees in M6.** Report lines carry the settled amount only; fee
   schedules, REVENUE accounts, and rounding stay deferred (recorded in the
   backlog, as M3 already anticipated).
4. **Ingest-and-match, immutable, idempotent at the API edge.** `POST
   /v1/conciliation/reports {from, to}` (under the M4 idempotency layer)
   fetches the report, matches, and writes report + lines in one
   transaction; nothing is ever updated afterwards (the schema grants no
   UPDATE). Re-ingesting the same window creates a new report — the history
   of reconciliations is itself an audit trail. Re-matching an existing
   report is YAGNI: internal settlement data is immutable post-settle.
5. **Direct internal-API consumption.** `PaymentsService` gains a read-only
   `listSettlements(from, to)` returning `SettlementView` records (in
   `payments.application`); `conciliation` injects the service directly —
   the repository's established idiom for internal APIs (`PaymentsServiceImpl`
   injects `AccountsService`; `AccountsServiceImpl` injects `Ledger`).
   Matching runs against the settled intent — the settlement record; the
   ledger itself is untouched by conciliation. Rejected: a consumer-owned
   port implemented inside payments (an indirection with no alternative
   provider to swap — unlike `PaymentNetwork`); reading payments tables from
   conciliation (violates the locked module rule).

## Architecture

```
POST /v1/conciliation/reports {from, to}          one transaction, one request
  └─ ConciliationService.ingest(from, to)
       ├─ reportSource.fetch(from, to)              ── psp_simulator adapter
       │    (SimulatorService.settlementReport: SUCCEEDED charges,
       │     settlement timestamp within [from, to))
       ├─ payments.listSettlements(from, to)        ── internal API (read-only)
       ├─ ReportMatcher.match(report, settlements)  ── pure function
       │    EXTERNAL lines: by charge → intent lookup
       │      none / not SETTLED        → MISSING_INTERNAL
       │      SETTLED, amount differs   → AMOUNT_MISMATCH   (Money.compareTo)
       │      SETTLED, amount equal     → MATCHED
       │    INTERNAL lines: settled intents absent from the report
       │      → MISSING_EXTERNAL
       ├─ INSERT settlement_report (status CONCILED iff every line MATCHED,
       │    else OPEN; per-status counts)
       └─ INSERT report_line xN
  201 + {reportId, from, to, status, counts…}
```

Both sides use the same half-open window `[from, to)` on the settlement
timestamp — SQL predicate `settled_at >= :from and settled_at < :to` — so
`MISSING_EXTERNAL` is well-defined. Amount comparison is `Money.compareTo`
(scale-insensitive: `10.0` equals `10.0000`) — the codebase's standing
discipline.

A duplicate `chargePublicId` within one fetched report is a network anomaly
the ingest rejects with 400 (the simulator never emits one; the unique
constraint is the backstop).

## Components

```
conciliation/
  application/ConciliationService.java      ingest + match orchestration (@Transactional)
  application/SettlementReportSource.java   port: fetch(from, to) → List<NetworkSettlement>
  application/NetworkSettlement.java        record (chargePublicId, Money amount, Instant settledAt)
  application/SettlementReport.java         record (Instant from, Instant to, List<NetworkSettlement>)
  application/ReportMatcher.java            pure: match(report, internal) → MatchOutcome(lines, summary)
  application/MatchedLine.java              record (origin, chargePublicId, reportedAmount,
                                             internalIntentPublicId, internalAmount, matchStatus)
  application/MatchSummary.java             record (int matched, amountMismatched,
                                             missingInternal, missingExternal, boolean conciled)
  application/ConciliationStore.java        port: insertReport(+lines) in caller's tx
  infrastructure/JdbcClientConciliationStore.java
  infrastructure/SimulatorSettlementReportSource.java   (implements the port via SimulatorService)
  interfaces/ConciliationReportsController.java + dto/ (CreateReportRequest,
                                             ReportSummaryResponse, ReportDetailResponse,
                                             ReportLineResponse)

payments/application/PaymentsService.java     + List<SettlementView> listSettlements(from, to)
payments/application/SettlementView.java      record (intentPublicId, accountPublicId,
                                              chargePublicId, Money amount, Instant settledAt,
                                              journalTransactionPublicId)
payments/application/PaymentsServiceImpl.java + repo delegation
payments/application/PaymentsRepository.java  + List<PaymentIntent> findSettledBetween(from, to)

psp_simulator/application/SimulatorService.java      + settlement report query
psp_simulator/application/NetworkSettlement.java     record (chargePublicId, Money amount,
                                                     Instant settledAt)
psp_simulator/interfaces/SimulatorController.java    + GET /simulator/settlement-report
```

The two `NetworkSettlement` records — one in each module — are intentional:
each side owns its vocabulary and the adapter maps between them (the same
shape as `PaymentNetwork`/`ChargeStore`). The simulator's settlement
timestamp is the SUCCEEDED charge's `updatedAt` — the instant of the pay
transition; the payments side's is `payment_intent.settled_at`.

## REST contract

| Endpoint | Behavior |
| --- | --- |
| `POST /v1/conciliation/reports` | `@Idempotent` (M4). Body `{from, to}` ISO-8601 instants; `from < to` enforced → 400. Fetch, match, persist immutably; **201** with `{reportId, from, to, status, matched, amountMismatched, missingInternal, missingExternal}`. Duplicate charge lines in the source → 400 `duplicate settlement lines`. |
| `GET /v1/conciliation/reports` | Newest first, limit 50, summaries only. |
| `GET /v1/conciliation/reports/{id}` | Full report: header + every line (`origin`, `chargeId`, `reportedAmount`, `internalIntentId`, `internalAmount`, `matchStatus`). 404 unknown. |

Operator surface: `GET /simulator/settlement-report?from&to` returns the raw
network lines (no key required — simulator routes are key-free).

## Persistence — `V9__conciliation_schema.sql`

```sql
create schema conciliation;

create table conciliation.settlement_report (
  id                  bigint generated always as identity primary key,
  public_id           uuid not null default gen_random_uuid() unique,
  period_from         timestamptz not null,
  period_to           timestamptz not null,
  status              text not null check (status in ('OPEN','CONCILED')),
  matched_count       int not null,
  amount_mismatched_count int not null,
  missing_internal_count  int not null,
  missing_external_count  int not null,
  created_at          timestamptz not null default now()
);

create table conciliation.report_line (
  id                       bigint generated always as identity primary key,
  report_id                bigint not null references conciliation.settlement_report(id),
  origin                   text not null check (origin in ('EXTERNAL','INTERNAL')),
  charge_public_id         uuid not null,
  reported_amount          numeric(19,4),
  internal_intent_public_id uuid,
  internal_amount          numeric(19,4),
  match_status             text not null check (match_status in
                           ('MATCHED','AMOUNT_MISMATCH','MISSING_INTERNAL','MISSING_EXTERNAL')),
  unique (report_id, charge_public_id)
);

grant usage on schema conciliation to nummus_app;
grant select, insert on conciliation.settlement_report, conciliation.report_line
  to nummus_app;
-- No update grant: reports are write-once artifacts; corrections are new reports.
```

## Error vocabulary

- `from >= to` → 400 (bean/service validation, existing `badRequest` path).
- Duplicate charge lines from the source → 400 with a duplicated charge id
  in the detail: `DuplicateSettlementLinesException` in
  `conciliation.application`, with its own handler entry returning
  problem+json (the `IdempotencyKeyReuseException` pattern).
- Unknown report → 404 (`UnknownConciliationReportException`, added to the
  existing notFound handler group).

## Testing

- **Matcher (pure unit)**: each of the four states; window boundaries
  (settled exactly at `from` included, at `to` excluded — both sides);
  scale-insensitive amount equality (`10.0` vs `10.0000`); summary counts and
  `CONCILED` iff zero divergences.
- **Schema + roles**: V9 shape, write-once grants (insert/select only, no
  update to deny), `unique(report_id, charge_public_id)`, lifecycle under
  `nummus_app`.
- **Payments query**: `findSettledBetween` window semantics (CREATED/FAILED
  intents excluded; settled-at boundaries).
- **Simulator report**: SUCCEEDED-only, window on settlement timestamp,
  operator REST endpoint.
- **Conciliation store**: report + lines round-trip, counts.
- **REST E2E**: full happy path (settle two intents via the simulator →
  ingest → every line MATCHED, status CONCILED); MISSING_INTERNAL via an
  orphan charge (`simulator.create` + `pay` with no intent — no DB
  tampering); AMOUNT_MISMATCH by DB-side amount tampering of a settled
  charge (the suite's established idiom for fault injection);
  MISSING_EXTERNAL via a window that excludes one settlement; idempotent
  replay of the ingest POST; `from >= to` → 400.
- **Architecture**: conciliation may depend only on `payments.application`
  and `psp_simulator.application`.

## Build constraint (standing, from M5)

No Maven/JVM runs on the workstation. Implementers edit and commit without
running tests; verification runs on the megalan server via the containerized
build (`~/nummus-ci` + `nummus-m2` volume), and the controller feeds the
summary back into each task's review.

## Out of scope (recorded to prevent re-litigation)

- Fees, fee schedules, REVENUE accounts, rounding (backlog — M3 deferred
  them here; they stay deferred).
- Re-matching existing reports, scheduled automatic reconciliation, report
  export (CSV), divergence notifications (webhook on divergence), partial
  matching on amount-only, multi-currency.
- Merchant authentication scoping of reports (global operator surface until
  auth lands, like every other endpoint).
