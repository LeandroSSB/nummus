# M2 backlog

Engineering notes carried over from the M1 review. None are defects in M1's
shipped behavior — the ledger's invariants hold in both enforcement layers —
but they should be addressed or consciously re-triaged as M2 (accounts and
REST API skeleton) takes shape.

## Behavior and API

- **Commit-time exception translation.** An account frozen between the
  service's fail-fast validation and COMMIT makes the deferred balanced
  trigger raise at commit; that SQLSTATE `P0001` surfaces as a raw Spring
  exception instead of a domain one. The deterministic paths (freeze before
  `post`/`reverse`) already map to `AccountNotActiveException`; the race
  window needs a single mapping point at the module boundary — which M2's
  REST error handling will want anyway.
- **Unfreeze API.** The spec allows `FROZEN ↔ ACTIVE`, but the `Ledger` port
  only exposes open/freeze/close. Decide whether unfreeze belongs on the port
  when the accounts module lands.
- **`Money` comparability.** `Money` exposes `compareTo` but does not
  implement `Comparable`; its record `equals`/`hashCode` are scale-sensitive.
  The codebase consistently compares with `compareTo` — keep that discipline
  and implement `Comparable` when ordering collections of amounts appear.

## Adapter and schema details

- **Scale normalization at the persistence boundary.** `Money.ofBrl` keeps
  the input scale in memory while `numeric(19,4)` normalizes in the
  database. Harmless under the `compareTo` discipline; add `setScale(4)` at
  the adapter boundary if a canonical scale is ever required.
- **`findTransaction` postings query** previously carried an unused
  self-join (removed post-M1); the header query legitimately joins to expose
  `reversal_of` as a public id.
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
