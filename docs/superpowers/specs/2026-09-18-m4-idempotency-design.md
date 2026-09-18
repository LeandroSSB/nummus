# M4 — Idempotency layer for merchant APIs design

**Date:** 2026-09-18 · **Status:** approved in brainstorming
**Builds on:** M1 ledger core, M2 accounts + REST skeleton, M3 payment intents + PSP simulator

## Goal

Make merchant-facing writes safe to retry. Every merchant-facing POST
requires an `Idempotency-Key`; the first execution's response is stored
in the same transaction that performs the business write, and retries
replay that response verbatim. A retry can never double-execute: the
reservation, the business write, and the response storage commit
atomically, or nothing does.

## Decisions (locked in brainstorming)

1. **Required on all merchant-facing POSTs.** `POST /v1/accounts`,
   `POST /v1/accounts/{id}/freeze|unfreeze|close`, and
   `POST /v1/payment-intents` all require the header. Missing or invalid
   header → `400 idempotency-key-missing`. Simulator endpoints are
   operator-facing and stay key-free.
2. **Global key uniqueness.** A key identifies one logical operation
   across the API surface. The request fingerprint is
   `SHA-256(method + "\n" + path + "\n" + raw body bytes)`. Same key +
   different fingerprint → `422 idempotency-key-reuse`.
3. **24-hour retention.** `expires_at = created_at + ttl` (default
   `PT24H`, configurable). Expired-but-not-yet-purged rows are reclaimed
   by a guarded UPDATE and the operation re-executes as new. A scheduled
   job purges expired rows hourly.
4. **Only 2xx responses are stored.** Domain errors (404, 409, 422) and
   validation errors (400) roll the transaction back — nothing is
   stored, and a retry re-executes. Every current error path is
   deterministic and side-effect-free, so re-execution reproduces the
   same error: observable behavior equals replay, without storing
   responses for failed writes. 5xx is never stored (uncertain state —
   retry must re-execute), which falls out naturally since unhandled
   exceptions roll back the reservation too.
5. **Mechanism: transactional controller-method aspect.** A web filter
   validates the header and caches the request body; an `@Around` aspect
   on `@Idempotent` handler methods opens the transaction
   (`TransactionTemplate`, default `REQUIRED` propagation — controllers
   are not transactional, so it creates one), reserves the key, invokes
   the handler, serializes the response, and stores it — all inside that
   transaction. Business services (`@Transactional REQUIRED`) join it.
   Rejected alternatives: (a) servlet filter with store-after-render —
   cannot join the business transaction without `UnexpectedRollbackException`
   hazards when exception handlers render 4xx inside the outer tx, and
   leaves a crash window between business commit and response store;
   (b) `UNIQUE(idempotency_key)` inside each module's schema — leaks a
   cross-cutting concern into module schemas, returns current state
   instead of the stored response, and does not fulfill the locked
   "responses are stored and replayed" decision.
6. **Backlog item folded in:** `@Digits(integer = 15, fraction = 4)` on
   `CreateIntentRequest.amount` — out-of-range amounts now fail with a
   clean 400 instead of a 500 at INSERT time.

## Architecture

Idempotency is cross-cutting infrastructure, not a business module (the
locked module list has none for it). It lives in the shared interfaces
layer, package `com.leandrossb.nummus.interfaces.idempotency`, following
the precedent of the shared `GlobalExceptionHandler`.

```
HTTP request
  └─ IdempotencyWebFilter (highest precedence, merchant routes only)
       ├─ no/blank/oversized Idempotency-Key → 400 idempotency-key-missing
       └─ wraps request with cached body → chain proceeds
  └─ DispatcherServlet → @Idempotent handler method
       └─ IdempotencyAspect @Around, inside TransactionTemplate:
            1. fingerprint = SHA-256(method, path, cached body)
            2. INSERT reservation row (key, fingerprint, expires_at)
               ├─ unique violation → load row:
               │    ├─ fingerprint mismatch → 422 idempotency-key-reuse
               │    ├─ expired → guarded reclaim, re-execute as new
               │    └─ stored response → replay verbatim +
               │      `Idempotency-Replayed: true`
               └─ inserted → proceed:
                    3. invoke handler → ResponseEntity
                    4. serialize body (ObjectMapper), attach response
                       (status, content-type, location, body) to the row
            5. COMMIT (reservation + business writes + response together)
```

### Why the aspect sees no intermediate states

Postgres exposes no uncommitted rows to concurrent transactions, and the
reservation and response attachment happen in one transaction — a
committed row always carries its response. There is no "reserved but
empty" state to handle: a concurrent duplicate INSERT blocks on the
unique index until the first transaction resolves. First commits →
violating INSERT → stored response replayed. First rolls back → the
duplicate INSERT succeeds → the operation executes. In-flight duplicates
therefore wait for the winner, like Stripe's idempotency layer, and
execution is exactly-once per key.

### Crash windows

- Crash before commit → reservation, business writes, and response all
  roll back → retry executes cleanly.
- Crash after commit → response is already stored → retry replays it.
- Crash after commit but before the body reaches the socket → retry
  replays the stored response.

No window exists in which a business write committed but the response
was not stored.

### Error rendering stays where it is

The transaction wraps only the handler method, not the rendering.
A domain exception (e.g. `UnknownPaymentIntentException`) propagates
through the aspect, rolls the transaction back — exactly today's
semantics — and `GlobalExceptionHandler` renders the problem+json
outside the idempotency layer. Bean-validation failures
(`MethodArgumentNotValidException`) occur during argument resolution,
before the advised method runs, so validation 400s never enter the
idempotency transaction. Both classes re-execute on retry and reproduce
the same deterministic response.

## Components (`com.leandrossb.nummus.interfaces.idempotency`)

- `@Idempotent` — marker annotation on the five merchant-facing handler
  methods.
- `IdempotencyWebFilter` — `@Order(HIGHEST_PRECEDENCE)`, applied to
  POST requests under `/v1/**` (simulator routes live under
  `/simulator/**` and never see it — this path rule is defense in depth
  so a future merchant POST without `@Idempotent` still fails closed
  with 400); validates the header (1–255 chars, non-blank); renders
  `400 idempotency-key-missing` itself as problem+json — a filter runs
  outside the `DispatcherServlet`, so the advice never sees it — and
  caches the request body via a wrapping servlet request for
  fingerprinting.
- `IdempotencyAspect` — `@Around` on `@Idempotent`; runs the
  reserve / proceed / attach sequence inside `TransactionTemplate`;
  builds the replay `ResponseEntity` (raw stored body as `String` with
  the stored Content-Type and Location headers, plus
  `Idempotency-Replayed: true`).
- `StoredResponse` — record `(status, contentType, location, body)`.
- `IdempotencyStore` — port: `insert(reservation)`, `Optional<StoredRow>
  findByKey(String)`, `attachResponse(id, StoredResponse)`,
  `boolean reclaimExpired(String key, byte[] newFingerprint, Instant
  newExpiresAt)` (guarded UPDATE — claims the slot only if
  `expires_at <= now`, clearing any stale response; rowcount checked),
  `int purgeExpired(Instant now)`.
- `JdbcClientIdempotencyStore` — `JdbcClient` adapter, unique-violation
  translated by Spring's `DuplicateKeyException`.
- `IdempotencyPurgeJob` — `@Scheduled(fixedDelay = 1h)` calling
  `purgeExpired(now)`; `@EnableScheduling` on `Application`.
- `IdempotencyProperties` — `nummus.idempotency.ttl` (default `PT24H`);
  tests override it to exercise expiry without waiting.

## Domain vocabulary

- `IdempotencyKeyReuseException` → `422 idempotency-key-reuse` ("title":
  Idempotency key reused with a different request). Thrown by the aspect
  inside the `DispatcherServlet` dispatch, so it is handled by
  `GlobalExceptionHandler` and rendered as problem+json like the M3
  entries.
- The missing-key `400 idempotency-key-missing` is rendered directly by
  `IdempotencyWebFilter` (same problem+json shape; no advice entry,
  because the filter runs outside the advice's reach).

## Persistence — `V7__idempotency_schema.sql`

```sql
create table idempotency_keys (
  id uuid primary key default gen_random_uuid(),
  key text not null unique,
  request_fingerprint bytea not null,
  response_status int,
  response_content_type text,
  response_location text,
  response_body text,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);

create index idempotency_keys_expires_at_idx on idempotency_keys (expires_at);
```

Grants for `nummus_app` follow the V3 pattern (`SELECT`, `INSERT`,
`UPDATE`, `DELETE` — the purge job deletes). The table is owned by the
shared interfaces layer, not by a business module; no module references
it.

## REST contract after M4

| Request | Before | After |
| --- | --- | --- |
| POST without `Idempotency-Key` | executed | `400 idempotency-key-missing` |
| POST, first execution | executed | executed, `201`/`200` stored |
| Retry, same key + body | executed again | replayed: stored status, headers, body + `Idempotency-Replayed: true` |
| Retry, same key, different body | executed again | `422 idempotency-key-reuse` |
| Retry after 24h (row expired) | n/a | re-executes as new |
| GET endpoints | unchanged | unchanged (no key required) |
| Simulator endpoints | unchanged | unchanged (operator-facing, no key) |

Replay preserves the original status code (201 for creates, 200 for
transitions), `Content-Type: application/json`, `Location` where the
original set one, and the byte-exact body.

## Testing

- **REST (`IdempotencyRestApiTest`)**: missing key → 400 on all five
  endpoints; first call + retry → identical status/headers/body with
  `Idempotency-Replayed: true`; same key + different body → 422;
  different keys + same body → independent executions; 404/409 paths
  re-execute on retry and return the same error; expired key (short TTL)
  is reclaimed and re-executes; replay of `freeze`/`close` returns the
  stored 200.
- **Concurrency (`IdempotencyConcurrencyTest`)**: N parallel identical
  POSTs (same key) → exactly one payment intent created; every response
  identical (one 201 execution, N−1 replays). Mirrors the M3
  exactly-once settlement proof.
- **Purge job**: rows past `expires_at` deleted; unexpired rows kept.
- **Schema (`IdempotencySchemaTest`)**: V7 shape, unique constraint
  enforced, `nummus_app` grants, `gen_random_uuid()` default.
- **Amount bound**: `CreateIntentRequest.amount` beyond `numeric(19,4)`
  → 400 with a field error, not a 500.
- **Boundary**: simulator endpoints still work with no
  `Idempotency-Key` (operator surface unchanged).

## Out of scope (recorded to prevent re-litigation)

- Merchant/API-key authentication and per-merchant key scoping — no
  merchant identity exists yet; when auth lands, key uniqueness gains a
  merchant column via migration.
- Webhook-signed retry queues, batch idempotency, conditional requests
  (`If-Match`) — separate milestones.
- Backfilling the three outstanding ArchUnit bans from the M3 review
  (deliberately not folded into M4; extend when those packages are
  touched next).
- The accounts status-transition TOCTOU and the write-transaction hold
  across the network poll — remain open in the backlog.
