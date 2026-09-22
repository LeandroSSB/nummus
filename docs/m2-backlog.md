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

## From the M5 review

M5 delivered the transactional outbox with write-time fan-out, signed
at-least-once delivery, and bounded exponential backoff. Known bounds,
deliberate:

- **Single-process worker.** `fixedDelay` self-exclusivity is the only guard;
  a second instance would double-deliver (still at-least-once-correct, but
  wasteful). Scale-out needs `FOR UPDATE SKIP LOCKED` claiming.
- **No delivery retention/pruning.** `webhook_delivery` rows accumulate;
  add a retention job (and a `delete` grant) when volume demands it.
- **No manual redrive.** FAILED deliveries stay failed; a retry API is a
  natural follow-up.
- **`GET /deliveries` is unpaginated** (fixed limit 50) and unauthenticated,
  like every other endpoint until merchant auth lands.
- **Receiver-side replay tolerance is documented, not enforced** — the
  signature carries `t=`, but tolerance windows are the receiver's choice.
- **SSRF surface on webhook registration.** Endpoints accept any `http(s)`
  URL and the worker POSTs to it, with `lastResponseStatus` observable via
  the deliveries listing — a status-oracle primitive against loopback,
  link-local, and RFC1918 targets. Consistent with the pre-auth stage (all
  endpoints are unauthenticated today); when merchant auth lands, reject
  internal target ranges at registration and consider https-only.

## From the M6 review

M6 delivered settlement-report conciliation: the simulator emits reports,
a pure matcher classifies MATCHED / AMOUNT_MISMATCH / MISSING_INTERNAL /
MISSING_EXTERNAL, and reports persist write-once (no update grant —
corrections are new reports). Known bounds, deliberate:

- **Matching is at ingest only.** Reports are frozen verdicts; internal data
  is immutable post-settle, so a re-match could only change the verdict by
  changing the report's window — which is a new report.
- **No fees.** Report lines carry the settled amount only; fee schedules,
  REVENUE accounts, and rounding remain deferred (M3's deferral carries on).
- **Two clock domains at the window edges.** `payment_intent.settled_at` is
  written from the JVM clock while the simulator's `updated_at` uses the
  database clock, and lazy settlement can legitimately trail the charge's
  transition. Boundary-straddling settlements can therefore yield a spurious
  MISSING_INTERNAL/MISSING_EXTERNAL on the first ingest; re-ingesting heals
  it. Negligible on one NTP-synced host, but a real PSP adapter must define
  its settlement-timestamp contract (single clock domain or explicit skew
  budget) before the edge cases become money-relevant.
- **Listings are unpaginated** (50 most recent) and unauthenticated, like
  every other endpoint until merchant auth lands.
- **No scheduled reconciliation or divergence alerting** — ingest is manual
  (POST). A webhook-on-divergence is the natural follow-up once operators
  want push instead of pull.

## From the M7 review

M7 delivered merchant identity and API keys: operator-created merchants,
Bearer authentication (SHA-256 at rest, secret shown once, soft revocation),
and per-merchant scoping of accounts, intents, webhook endpoints, and
idempotency namespaces (operator POSTs keep a NULL-merchant namespace).
Known bounds, deliberate:

- **Operator surfaces stay open.** Merchant creation, the simulator, and
  conciliation require no credentials — an operator identity model is the
  natural next debt.
- **Key lifecycle minimums.** No expiry, rotation policy, or `last_used_at`
  tracking (a write per request); prefixes are display-only.
- **SSRF narrowed, not closed.** Webhook registration is now authenticated,
  but any http(s) target is still accepted — range rejection stays backlog.
- **No rate limiting** on authenticated routes; bearer lookups are one
  indexed query per request.
- **Merchants.application remains reachable** from business modules for the
  `SeedMerchant` bridge; retire it when a real migration-era consumer audit
  lands (or scope it behind a query port).

## From the M8 review

M8 delivered operator authentication: one-time env bootstrap, operator-key
lifecycle (mint/list/revoke), merchant creation and conciliation gated
(401 keyless, 403 role-mismatch both directions), simulator open by design.
Known bounds, deliberate:

- **Operators are role-level, not person-level.** One key equals "an
  operator"; no per-operator identity, audit attribution, or RBAC.
- **Bootstrap lockout is operational.** Revoking every operator key
  re-arms the bootstrap — recovery requires redeploying with the env
  token set; a stolen token plus a full revoke is a takeover path (token
  handling is deployment security).
- **No key expiry or last_used_at**; constant-time compare covers the
  bootstrap token only — key hashes are exact-match indexed lookups.
- **Simulator stays unauthenticated** (non-production harness); a real
  deployment replaces the network boundary entirely.
- **Bootstrap one-time-ness is check-then-act** in the service, not a
  database invariant — two concurrent requests holding the correct token
  can both mint. No capability gain (a token holder can already self-serve
  additional keys), so it stays a documented bound, not a schema constraint.

## From the M9 review

M9 delivered per-merchant fees: operator-managed rate+fixed schedule,
three-leg settlement (clearing gross debit, merchant net credit, system
REVENUE fee credit), settle-time rate wins, fee persisted as a fact on
the intent, and quote-vs-fact semantics on the merchant API. Known
bounds, deliberate:

- **No fee history or versioning.** Two mutable columns; changing a
  rate rewrites nothing retroactively but leaves no audit trail of
  who changed what when (operators remain role-level — the M8 bound).
- **Fees exist only at settlement.** Nothing on expiry or failure, no
  recurring or monthly charges, no minimum-fee floor below the fixed
  component.
- **The fee is capped at gross.** A fixed component larger than a
  small transaction charges only the gross; a fully capped settlement
  posts no merchant leg — the entire gross credits revenue — and
  merchants cannot go net negative through fees.
- **No revenue reporting endpoint.** The revenue balance is derivable
  from the ledger; analytics stay out.
- **Quote semantics are read-time.** Pre-settle `fee`/`netAmount` are
  estimates from the rate in force; only the settled fact is stored.
- **Sub-scale fee inputs round at the storage boundary.** A rate with
  more than six decimals or a fixed amount beyond four passes
  validation but is rounded by `numeric(9,6)`/`numeric(19,4)` on
  write; the stored (rounded) value is what settlement charges.
- **Webhook payload scales are mixed by design.** `amount` keeps the
  journal's four-decimal scale (a shipped contract field); `fee` and
  `netAmount` are centavos-minimal. Numerically identical under any
  decimal parser.

## From the M10 review

M10 hardened webhook delivery: two-layer SSRF enforcement (registration
400 plus delivery-time revalidation, redirects never followed),
merchant self-serve redrive with a fresh retry cycle, TTL pruning of
succeeded deliveries, and cursor pagination behind a response header.
Known bounds, deliberate:

- **Events are never pruned.** Payload rows accumulate; deliveries are
  the volume multiplier and carry the retention policy.
- **No DNS pinning.** The two-layer check trusts each resolution as it
  happens; pinning registration-time answers would break legitimate
  CDN churn.
- **Loopback http is the only internal exemption** — local receivers,
  and by extension anything that can bind the test host's loopback.
- **Pruning is single-process** like the delivery worker; scale-out
  needs the same SKIP LOCKED treatment.
- **No mTLS or per-endpoint retry budgets** — the signature scheme and
  the shared maxAttempts/backoff policy govern.
- **Cursors are opaque bookmarks.** A foreign-but-real delivery id used
  as `after` resolves and simply skips ahead within the caller's own
  rows; only unknown or pruned cursors yield an empty page. The listing
  stays merchant-scoped either way — no cross-tenant data.
- **DNS resolution is unbounded and unpinned.** Policy checks resolve
  per call with no timeout beyond the OS resolver; a blackholing
  authoritative NS can stall delivery ticks, and an attacker's DNS may
  answer differently between the pre-dial check and the POST within one
  attempt. Availability bound, accepted for a single-worker deployment.

## From the M11 design

M11 hardened the API surface: per-tenant token-bucket rate limiting between
authentication and body buffering, request-body caps at the idempotency
filter, and key lifecycle — mint-time expiry, best-effort `last_used_at`,
and self-serve rotation that retires the calling key at
`least(existing, now + grace)`. Known bounds, deliberate:

- **Limiter state is per-process.** A restart resets buckets (full burst
  quota after boot); a second instance enforces independently — the same
  single-process stance as the delivery worker and retention prune.
- **No per-IP throttling; unauthenticated routes are unthrottled.** The
  simulator stays open by design; an unauthenticated flood is an
  edge/deployment concern.
- **No per-merchant limit overrides** — global properties only.
- **`last_used_at` is one write per authenticated request.** The throttled
  async flush stays deferred.
- **Buckets are never evicted** — memory bounded by tenant count.
- **The cap guards the buffered merchant-write path only.** Simulator
  writes stay uncapped (non-production harness).
- **No per-route limit classes** — one bucket per tenant.

## From the M11 review

The whole-branch review found no production defect. Backlog-grade items it
raised, none merge-blocking:

- **New `nummus.*` knobs accept degenerate values.** `refill-per-second=0`
  permanently throttles after burst (Retry-After overflows to ~68 years);
  zero/negative capacity permanently 429s; `max-body-bytes=2147483647`
  overflows `readNBytes(max + 1)`. One uniform posture — `@Min(1)`-style
  binding validation vs documented-only — should cover the whole class.
- **Bucket growth is bounded by distinct tenants *plus distinct operator
  keys since start*** (rotation mints a new key id) — the "tenant count"
  wording in the M11 section above is the imprecise form.
- **Sub-millisecond `expiresIn`** passes `isPositive()` but truncates via
  `toMillis()` to a stillborn 201 key (`expires_at = now()`); guard
  `toMillis() >= 1`.
- **Test polish:** operator `last_used_at` test targets its row by
  `order by id desc` rather than the returned key id; no operator-side
  not-on-401 stamp assertion; `atCapBodyPassesThrough` doesn't pin chain
  execution (assert the chain's content type); forward timing assertions
  tolerate <2s stalls (widen to 5s at first flake).
- **Credentialed calls to unprotected routes** (a merchant key hitting the
  simulator) consume tenant quota — matches the letter of "unauthenticated
  passes through"; recorded as intended behavior.
- **Test fixtures:** `operatorAuth()` / `createMerchantAndGetKey()` are
  copy-pasted across six-plus classes; extract a shared fixture before the
  next milestone.
- **Spec-to-plan fidelity:** two spec-enumerated tests (the exact
  `expires_at = now()` boundary; a fault-injected stamp failure) were
  dropped at plan time without note — the stamp-failure `catch` path is
  untested by decision. Carry spec test lists verbatim or record drops.

## From the M12 design

M12 automated conciliation: scheduled re-ingest over tumbling windows whose
start self-heals past manual ingests and whose end holds back a 30s lag for
the M6 clock skew, operator webhook endpoints riding the NULL-merchant
namespace of the existing outbox, and a `conciliation.report_open` digest
per OPEN report. Recon also closed a latent M5-era leak: event fan-out was
unscoped, delivering one merchant's payment events to every merchant's
endpoints — fan-out now binds to the event's merchant (or NULL for
operators). Known bounds, deliberate:

- **Single-process scheduler and delivery worker** — scale-out needs the
  `SKIP LOCKED` treatment already documented for delivery and retention.
- **Digest-only alerting** — per-line divergence stays behind the report
  GET; no per-line push, no severity routing.
- **The operator endpoint set is role-level shared** — no per-operator
  ownership until audit attribution lands (the M8 bound carries).
- **The lag is a property, not an SLA** — 30s of alert latency buys skew
  safety; a real PSP adapter still owes the settlement-timestamp contract.
- **Empty scheduled windows advance silently** — a quiet system leaves no
  trace beyond the marker; observability of tick health is log-only.

## From the M12 review

The whole-branch review found no production defect. Items it raised:

- **A future-dated manual ingest stalls the scheduler silently.** The manual
  POST validates only `from < to`; a typo'd `to` in the future makes the
  self-healing start outrun the lagged now, and every tick no-ops without a
  log line until wall clock passes it. Fast-follow: reject `to` beyond
  `now + slack` on the manual route, or warn once when a no-op tick is
  caused by `max(period_to)` being ahead of now.
- **Failure-log polish:** a failed tick logs the cause at WARN then surfaces
  a stackless `UnexpectedRollbackException` at ERROR (rollback and retry are
  correct; the framing misleads). A `TransactionTemplate` around the window
  body would make the swallow real.
- **Test pins worth adding when the suites are next touched:** cross-catalog
  event-type rejections (operator registering a payment type; merchant
  registering `conciliation.report_open`); reverse-direction namespace
  isolation; operator-side pagination bounds/unknown-cursor (the controller
  mirrors the merchant one — extract a shared helper if a third copy
  appears).
- **Spec sketch alignment:** the M12 spec's event sketch shows a nested
  `window` object; the shipped envelope is flat `data.from`/`data.to` in the
  standard wrapper. Align the sketch next time the spec is touched.
- **V15 grants `insert` on `conciliation.ingest_state` to `nummus_app` but
  the app only UPDATEs** (the migration seeds the single row; the check
  constraint forbids a second). Harmless least-privilege excess.

## From the M13 design

M13 hardened operations: uniform startup validation on every `nummus.*`
knob (one binding error instead of a permanently-429ing or overflow-prone
runtime), the two-layer future-dated-ingest stall guard (route 400 plus a
once-per-episode scheduler warn), sub-millisecond `expiresIn` rejection,
`TransactionTemplate` failure logging with real atomicity, shared REST test
drivers, and the M12 review's pins. Known bounds, deliberate:

- **Validation is startup-time only** — runtime re-binding or refresh of
  properties stays out of scope.
- **The stall warn is per-process** — a second instance warns independently
  (the workers' single-process stance).
- **No pagination-helper extraction** — the two mirrored controllers are
  pinned independently; extraction waits for a third copy.

## From the M14 design

M14 closed the M8/M9 attribution threads: operator keys carry immutable
identity labels (rotations carry the label forward; pre-M14 keys read
`system`), and every fee-schedule change appends an attributed,
append-only history entry while the merchant's columns stay the cached
current value. Known bounds, deliberate:

- **Only fee changes are attributed** — merchant creation, key operations,
  and conciliation triggers stay unattributed; a general audit log can
  build on the label seam.
- **No RBAC and no operator entity** — labels are identity-light; grouping
  and roles can come later without history rewrites.
- **PUT retries append per delivery** — a network-level retry of the fee
  route leaves two identical entries; honest as "applied twice".
- **`system` is a sentinel, not an actor** — pre-M14 attribution is
  genuinely absent (NULL rendered as `system`).
- **Labels are shape-validated only** — non-blank, ≤64 chars, no
  uniqueness or i18n constraints; the key id disambiguates.

## From the M13 review

The whole-branch review found no production defect. Backlog-grade items it
triaged:

- **`latestReportEnd`'s empty-table path is exercised only incidentally** —
  a direct assertion against a wiped table would pin the null-safety
  regression the M13 plan's original snippet carried.
- **`ConciliationWorkerTest` cannot run as the sole `-Dtest`** — its static
  `@BeforeAll` precedes Flyway when no other class has started the context;
  focused runs must include a companion suite.
- **~15 added Java lines exceed 100 columns** (fee/deliveries/endpoints test
  suites and one exception message) — no lint gate; tighten when those files
  are next touched.

