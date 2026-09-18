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

M4 added the idempotency layer: stored responses replay verbatim, 2xx only —
4xx paths roll back and re-execute deterministically, which is observationally
equivalent to replay but is not storage; recorded here so nobody "fixes" the
error paths into storage without revisiting the `UnexpectedRollbackException`
hazard documented in the M4 spec.

## From the M4 review

The final whole-branch review hardened the reclaim into the re-execution
transaction (a failed re-execution can no longer leave a response-less,
unexpired key that 422s until TTL), added the aspect's own fail-closed key
guard (encoded-path filter bypasses now 400 instead of 500), and exercises
the V7 grants under `nummus_app` (`IdempotencyRolesTest`). Remaining open,
none merge-blocking:

- **Expiry uses two clocks.** The aspect judges expiry by the JVM clock
  while `reclaimExpired` judges by the database clock; a same-request retry
  inside the skew window (measured ~5s here) gets 422 instead of a replay.
  Judge both sides on the DB clock, or fall back to replay when the
  fingerprint and stored response match.
- **2xx-only storage is convention.** `toStoredResponse` stores whatever
  the handler returns; only the throw-to-signal-error discipline keeps 4xx
  out. A handler returning `ResponseEntity.badRequest()` would be stored
  and replayed silently. Enforce or assert when a handler is added that
  returns non-2xx envelopes.
- **`@Idempotent` handlers must return `ResponseEntity`.** A DTO-declared
  handler replays through a raw `ResponseEntity` and fails the proxy's
  return-type check (`ClassCastException`). Convention not yet written into
  the annotation's javadoc — add it when the file is next touched.
- **Aspect catches `DuplicateKeyException` from any statement** in the
  transaction, not only `store.insert`; a business unique-constraint
  violation would surface as the raw DKE (500). Unreachable today (all
  unique columns are server-generated UUIDs); revisit when a
  merchant-influenced unique column lands. Related: a purge racing the
  replay lookup can make `findByKey` empty after a lost insert — the DKE
  rethrows (500), self-healing on retry.
- **Unbounded request-body buffering** in `IdempotencyWebFilter`
  (`readAllBytes` with no cap). Add a 413 guard together with auth and
  rate limiting.
- **Idempotency and the web layer.** Duplicate `Idempotency-Key` headers
  are silently first-wins; the filter's 400 charset is implicit
  (ASCII-only message today); replayed `Content-Type` and the
  `application/problem+json` content type of the 400s are unasserted in
  tests; `nummus.idempotency.ttl` binding is untested since the expiry
  test moved to DB-side aging; the fingerprint hashes the raw URI, so one
  key reused across path encodings is a mismatch 422 (fail-closed, fine).
- **ArchUnit.** Nothing bans business modules from depending on
  `..interfaces.idempotency..` (controllers-only by convention). Extend
  the rules when the package is next touched — alongside the three M3
  bans still unchecked above.
