# M19 — Money-Out Conciliation Design

**Goal:** Settlement reports cover every instruction the network executed —
charges (today) plus payout transfers and charge refunds. The report schema,
matcher, and feeds generalize from a charge-shaped key to a subject key
`(type, network public id)`; the operator surface, scheduled window, and
digest stay single and unchanged in shape.

**Spec status:** approved design — the generalized-report topology was
validated with the project owner; strict-mirror semantics (internal `SETTLED`
vs network `SUCCEEDED`) follows the stance M6 set for charges.

## Scope

1. **The external report gains two subject kinds.** The simulator's
   settlement report for a window unions the `SUCCEEDED` charges (today)
   with `SUCCEEDED` payout transfers and `SUCCEEDED` charge refunds,
   timestamped by `updated_at` in-window — the charge precedent applied
   verbatim. Each line carries its kind.
2. **Report lines become subject-shaped (V21).** `conciliation.report_line`
   keys lines by `subject_type` ∈ (`CHARGE`, `PAYOUT_TRANSFER`,
   `CHARGE_REFUND`) + `subject_public_id`; `charge_public_id` and
   `internal_intent_public_id` rename to `subject_public_id` and
   `internal_public_id`; uniqueness becomes
   `(report_id, subject_type, subject_public_id)`. Existing rows backfill
   to `CHARGE` — write-once history preserved, old reports read identically
   under the new shape. `settlement_report` is untouched.
3. **Internal feeds for money-out.** Payments exposes
   `PayoutsService.listSettlements(from, to)` and
   `RefundsService.listSettlements(from, to)` over new
   `findSettledBetween` queries mirroring the charge feed; conciliation
   consumes them through the payments internal API, exactly as it consumes
   `PaymentsService.listSettlements` today.
4. **The matcher re-keys, not rewrites.** `ReportMatcher` keys by
   `(subjectType, subjectPublicId)`; duplicate rejection fires per kind
   (the same UUID under two kinds is not a duplicate); the leftover-internal
   pass emits `MISSING_EXTERNAL` per kind; the tally aggregates across
   kinds with its shape unchanged.
5. **HTTP contracts.** `POST /v1/conciliation/reports` is unchanged (its
   window now ingests all kinds); report-line responses re-shape
   (`subjectType` / `subjectId` / `internalId`); the simulator report line
   gains `kind`. No consumers exist beyond the test suites — clean shape
   change, asserted in the REST pins.

## Non-goals (bounds, deliberate)

- **Strict mirror semantics** — internal `SETTLED` vs network `SUCCEEDED`
  only, no pending-match. An executed-but-never-read money-out instruction
  surfaces as `MISSING_INTERNAL`, and the frozen verdict never heals — the
  same noise charges have carried since M6. The reservation legs keep the
  money accounted (payout/refund reserve), so this flags reconciliation
  debt, not lost money; reading the resource settles it.
- **No per-kind tally split** — the digest and summary stay aggregate
  counts; operators drill per-line through the report GET as today.
- **No retroactive re-match** — pre-M19 reports keep their verdicts under
  the backfilled shape (the M6 ingest-only bound carries).
- **No new worker, marker, lag, or event** — the M12 window machinery,
  30s lag, future-dated-ingest guard, and `conciliation.report_open` digest
  ride unchanged.
- **Clock-domain bound carries** — `settled_at` (JVM clock) vs
  `updated_at` (DB clock) at window edges for the two new subject kinds;
  the lag absorbs it exactly as for charges, and a real PSP adapter still
  owes the settlement-timestamp contract.
- **No listing surfaces, no void surface** — payout/refund listings and
  early abort stay on the backlog.

## Matching semantics (per line)

Join keys: charge → `charge_public_id`; payout → `transfer_public_id`;
refund → `network_refund_public_id` — always the network instruction's
public id, on both sides. Verdicts and amount comparison (`Money.compareTo`,
scale-insensitive) are unchanged. Reachability is asymmetric by honesty:
`MISSING_INTERNAL` occurs in normal operation through lazy settlement
(execute, never read); `AMOUNT_MISMATCH` and `MISSING_EXTERNAL` require
tampering or window skew because internal `SETTLED` implies an observed
`SUCCEEDED` — tests reach them through SQL manipulation on the simulator
tables, the M6 suite's precedent.

## Schema (V21 — one migration)

`V21__money_out_conciliation.sql`: backfill `subject_type = 'CHARGE'`,
rename the two columns, swap the unique constraint (constraints cannot be
altered in place — the V20 pattern). Grant coverage for renamed columns is
verified at plan time and extended if the V9 grants are column-scoped.

## Module placement

`conciliation` (matcher keying, `NetworkSettlement`/`SettlementView`
vocabulary, persistence, REST line shapes), `payments` (two
`listSettlements` internal APIs + repository queries), `psp_simulator`
(`findSucceededBetween` on `TransferStore`/`RefundStore`, report union).
ArchUnit unchanged — every dependency lands inside existing edges
(conciliation → payments application, conciliation → simulator through the
existing report source).

## Testing strategy

TDD per plan task, remote CI only (megalan), house idioms, sweeps carried
with every copied recipe. Coverage:

- Matcher unit: per-kind keying; cross-kind UUID non-collision; duplicate
  rejection per kind; leftover-internal pass per kind.
- Integration end-to-end: a settled payout ingests to `MATCHED` as
  `PAYOUT_TRANSFER`; a settled refund likewise as `CHARGE_REFUND`; charges
  stay `MATCHED` (regression pin on the generalization).
- The lazy-noise pin: execute the transfer, never read the payout, ingest
  → `MISSING_INTERNAL`.
- `AMOUNT_MISMATCH` and `MISSING_EXTERNAL` via SQL tampering on the
  simulator tables (M6 precedent).
- Backfill pin: a report created pre-V21 shape reads as `CHARGE` lines
  under the new response contract.
- REST shape pins for the re-shaped line responses and the simulator
  report's `kind` field.

## Migration / rollout notes

- V21 is additive-generalizing: backfill + renames + constraint swap, no
  verdict changes, no data migration beyond the backfill constant.
- The one behavior change operators see: money-out executions now appear
  in reports and digests — the counts grow, the shapes do not.
