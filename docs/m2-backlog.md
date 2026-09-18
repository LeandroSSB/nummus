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

- **Status-guarded transitions.** `AccountsServiceImpl.transition` and the
  repository UPDATE are unguarded against concurrent transitions (TOCTOU).
  Postings stay safe — the ledger trigger is authoritative — but the
  accounts row can diverge. Fix with a status-guarded UPDATE or
  `SELECT ... FOR UPDATE` when M3 adds concurrent callers.
- **Roles coverage.** Probe the `closed_at` column grant under `nummus_app`
  and assert SQLSTATE `42501` instead of message substrings.
- **End-to-end commit-time trigger → 409 HTTP test.** First reachable
  through the real stack when M3 adds money movement.
- **`GlobalExceptionHandler` scoping.** Relocate or `basePackages`-scope it
  when a second module's controllers arrive; it is app-global today.
- **Error body consistency.** Statement lines now carry `currency` per
  spec; 500 bodies still use Boot's default error JSON (problemdetails
  can unify later).
