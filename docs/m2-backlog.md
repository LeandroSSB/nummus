# M2 backlog

> Updated after M2: the unfreeze API, natural-sign presentation, and
> commit-time exception translation are implemented; remaining notes stay
> recorded for later milestones. The dead self-join previously noted in
> `findTransaction`'s postings query was removed post-M1; the header
> query's join legitimately remains to expose `reversal_of` as a public id.

Engineering notes carried over from the M1 review. None are defects in
shipped behavior — the ledger's invariants hold in both enforcement
layers — but they should be addressed or consciously re-triaged as later
milestones take shape.

## Behavior and API

- **`Money` comparability.** `Money` exposes `compareTo` but does not
  implement `Comparable`; its record `equals`/`hashCode` are scale-sensitive.
  The codebase consistently compares with `compareTo` — keep that discipline
  and implement `Comparable` when ordering collections of amounts appear.

## Adapter and schema details

- **Scale normalization at the persistence boundary.** `Money.ofBrl` keeps
  the input scale in memory while `numeric(19,4)` normalizes in the
  database. Harmless under the `compareTo` discipline; add `setScale(4)` at
  the adapter boundary if a canonical scale is ever required.
- **`ALTER DEFAULT PRIVILEGES`** in V3 binds to the migrating role. Correct
  while Flyway runs as a single role; revisit if migrations ever run under
  different roles.

## Test-suite polish

- Cover directly: posting to a `CLOSED` account (today only exercised via
  `FROZEN` plus the trigger's `<> 'ACTIVE'` check), the `gen_random_uuid()`
  column default, and `PostingDraft`'s null-amount rejection.
- The in-memory fake's `statementLines` has no tiebreaker for equal
  `bookedAt` instants (the PostgreSQL adapter owns authoritative ordering),
  its `insertAccount` overwrites duplicate public ids, and its
  duplicate-reversal check is not atomic — acceptable for single-threaded
  unit use, worth hardening before any concurrent test reuses it.
- The concurrency proof joins workers with per-future timeouts against a
  `@Timeout(120)` backstop; a single deadline-based join would be cleaner.

## Performance notes

- The balanced-transaction trigger is `FOR EACH ROW` and deferred, so a
  transaction with N postings re-aggregates N times at commit (O(N²)).
  Irrelevant at two-leg entries; revisit for bulk postings.

## From the M2 review

M3 added the frozen-account settle → 409 path over HTTP (fail-fast)
and relocated the shared error advice; payment-intent state
transitions are status-guarded. The mid-flight commit-time
trigger → 409 case remains covered at handler level only. The items
below remain open:

- **Status-guarded account transitions.** The M2-era TOCTOU on
  account status changes (`AccountsServiceImpl.transition` and the
  accounts/ledger repository UPDATEs) remains — M3 guarded
  payment-intent transitions only. Fix with a status-guarded UPDATE
  or row lock when concurrent account transitions become real.
- **Roles coverage.** Probe the `closed_at` column grant under `nummus_app`
  and assert SQLSTATE `42501` instead of message substrings.
- **Error body consistency.** Only unhandled 500s use Boot's default
  error JSON — `ChargeAmountMismatchException` already returns
  problem+json via the M3 `invariantBreach` handler (problemdetails
  can unify the rest later).
- **Amount magnitude bound.** `CreateIntentRequest.amount` has no upper
  bound; an amount beyond `numeric(19,4)` fails at INSERT and surfaces
  as 500. Add `@Digits(integer = 15, fraction = 4)` (or `@DecimalMax`)
  for a clean 400.
- **ArchUnit under-encoding.** The M3 rules leave three spec-stated
  bans unchecked: psp-simulator → `..ledger.application..`;
  ledger/accounts → `..psp_simulator..`; payments →
  `..ledger.interfaces..`/`..accounts.interfaces..`. The code
  complies; extend the rule lists when touched next.
- **Write transaction across the network poll.** `PaymentsServiceImpl.get`
  holds its write transaction across the `PaymentNetwork.getCharge`
  call — invisible with the in-process simulator, but a real PSP
  adapter would hold a pooled connection across an HTTP call. Bound
  the hold or poll before opening the write transaction when a real
  adapter lands.
