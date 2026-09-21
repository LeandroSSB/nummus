# M11 — API Hardening

**Date:** 2026-09-21
**Status:** approved design, pending implementation plan

## Goal

Close the three most-cited availability and lifecycle gaps from the milestone reviews: authenticated routes gain per-tenant rate limiting, the request-body buffering path gains a size cap, and API keys (merchant and operator) gain expiry, `last_used_at` tracking, and zero-downtime rotation with a grace window.

## Product decisions (locked)

1. **Rate limiting is a per-tenant token bucket, in memory.** Merchant requests bucket by **merchant public id** (not per key — rotation must not buy fresh quota); operator requests bucket by **operator key public id**. Limits are global config properties split by role; no per-merchant overrides, no schema.
2. **Rotation has a grace window.** `POST .../current/rotate` mints a fresh key (secret shown once) and retires the calling key at `expires_at = now + grace`; the old key keeps working until then, after which it fails 401. Grace is a property (`300s` default), not per-request.
3. **`expires_at` is dual-purpose:** operator-chosen lifetime at mint (`expiresIn`, optional, ISO-8601 duration) and rotation grace — one column, one auth check.
4. **`last_used_at` is one best-effort write per successful authentication** — a failure to record it logs and never fails the request.
5. **429 and 413 are pre-controller responses:** no idempotency record is stored; a later retry with the same `Idempotency-Key` executes fresh — consistent with the existing "4xx paths roll back and re-execute" semantics.
6. **The body cap lives where buffering lives** — `IdempotencyWebFilter`, the only unbounded `readAllBytes` on the path (M4 review). Simulator writes stay uncapped (non-production harness, by design).

## Rate limiting

**Filter.** `interfaces.auth.RateLimitFilter`, `@Order(Ordered.HIGHEST_PRECEDENCE + 500)` — after `MerchantAuthFilter` (`+0`, which resolves the tenant) and before `IdempotencyWebFilter` (`+1000`, which buffers bodies). A rate-limited request is rejected before a single body byte is read.

- Reads the request attributes the auth filter already sets: `auth.merchant` → bucket key `merchantPublicId`; `auth.operator` → bucket key `keyPublicId`. Neither attribute → pass through untouched (unauthenticated requests, the 401 path, the open simulator).
- Bucket state: `ConcurrentHashMap<String, TokenBucket>`, mutation synchronized on the bucket. Idle buckets are never evicted — memory bounded by tenant count (documented bound).

**Token bucket.** Hand-rolled, no dependency, no background threads: `{capacity, tokens, lastRefillNanos}`; every `tryConsume` first refills lazily — `tokens = min(capacity, tokens + elapsedNanos * refillPerSecond / 1e9)` — then consumes one token or refuses. `Clock`-injected for deterministic tests. Burst up to `capacity`, sustained `refill-per-second`.

**Config** (`nummus.ratelimit.*`, `@ConfigurationProperties` in the house style of `WebhookProperties`):

| Property | Default | Meaning |
| --- | --- | --- |
| `merchant.capacity` | `600` | burst ceiling per merchant |
| `merchant.refill-per-second` | `10` | 600/min sustained |
| `operator.capacity` | `120` | burst ceiling per operator key |
| `operator.refill-per-second` | `2` | 120/min sustained |

**Exhaustion response.** `429`, header `Retry-After: <ceil((1 − tokens) / refillPerSecond)>` seconds, body `application/problem+json` rendered by the filter itself — a filter runs outside `@ControllerAdvice`'s reach, same pattern as `IdempotencyWebFilter`'s 400.

## Key lifecycle

**Migration — `V14__api_key_lifecycle.sql`:**

```sql
alter table merchants.api_key
  add column expires_at   timestamptz null,
  add column last_used_at timestamptz null;
alter table merchants.operator_key
  add column expires_at   timestamptz null,
  add column last_used_at timestamptz null;
```

No new grants — V10/V11 already grant `update` on both tables to `nummus_app`.

**Auth check.** `MerchantAuthentication` and `OperatorAuthentication` (where `status = 'ACTIVE'` is enforced today) tighten to `status = 'ACTIVE' and (expires_at is null or expires_at > now())`, judged on the database clock in the same statement that resolves the key. An expired key is indistinguishable from a revoked one: **401**, existing vocabulary, no new error type. Grace enforcement is lazy — nothing flips the row at grace end.

**Mint-time expiry.** The mint surfaces (`POST /v1/me/api-keys`, `POST /v1/operator/api-keys`) accept an optional `expiresIn` (ISO-8601 duration, e.g. `P90D`); omitted = never expires. Validation: parseable and strictly positive, else 400 problem+json.

**Rotation.**

- Routes: `POST /v1/me/api-keys/current/rotate` (merchant, Bearer) and `POST /v1/operator/api-keys/current/rotate` (operator) — they retire **the calling key**. Both `@Idempotent`: a replayed rotation returns the same stored response, so the same new secret is handed back, never a second key.
- Behavior: mint a fresh key (accepting the same optional `expiresIn` for the new key); retire the calling key with `expires_at = least(coalesce(expires_at, infinity), now() + grace)` — rotation never *extends* an existing nearer expiry, it only ever shortens. Response: the mint response shape plus `oldKeyExpiresAt`.
- Grace: `nummus.api-keys.rotation-grace` (duration, default `PT5M`).
- An expired key never reaches rotation — it 401s at auth first.

**`last_used_at`.** On every successful authentication, the auth port issues `update <key table> set last_used_at = now() where id = :id` — fire-and-forget in the house's best-effort sense: any failure logs at warn and the request proceeds. The throttled-flush alternative from the M7 review stays a documented bound, not built.

## Body cap

`nummus.http.max-body-bytes` (default `1048576` — 1 MiB; merchant writes are small JSON). Enforced in `IdempotencyWebFilter` after the key-header validation, two layers:

1. **Pre-read:** `Content-Length` present and `> max` → 413 immediately, stream untouched.
2. **Backstop:** the `readAllBytes()` becomes `readNBytes(max + 1)`; a result longer than `max` → 413. Covers absent, lying, and chunked bodies.

Response: `429`-style rendering — `413`, `application/problem+json`, written by the filter. Like the 429, it is pre-controller and leaves no idempotency record.

## Error handling

- `429` / `413`: rendered by their filters, `application/problem+json`, no stored idempotency.
- Expired key → 401 via the existing unauthorized vocabulary (`MerchantUnauthorizedException` / `OperatorUnauthorizedException` paths).
- `expiresIn` malformed or non-positive → 400 problem+json.
- Rotation of an unknown/foreign key is unreachable by construction (the route addresses the calling key, which just authenticated).

## Security and invariants

- Limits bind to the tenant, not the credential — key churn cannot reset quota.
- Rotation is self-serve on the calling key only; no route accepts a key id, so one merchant cannot rotate another's key.
- `expires_at` narrowing is monotonic per key: `least(existing, now + grace)` never extends a lifetime.
- The limiter runs after authentication, so only authenticated principals consume and face 429s; an unauthenticated flood is a deployment/edge concern (documented bound).
- `last_used_at` writes never gate authentication — availability over observability.

## Testing

1. **Bucket (pure unit, fake clock):** refill accrues over elapsed time; tokens clamp at capacity; burst drains to zero; sub-token elapsed time does not consume; refuse at zero tokens.
2. **Limiter REST:** capacity + 1 rapid requests → 429 with `Retry-After` and problem+json; advancing the clock past a refill window recovers; merchant and operator buckets isolated; two merchants isolated; unauthenticated request passes through; a 429 leaves no idempotency row.
3. **Schema:** V14 columns exist, nullable, no default; `nummus_app` needs no new grants (asserted).
4. **Expiry:** key with past `expires_at` → 401; boundary `expires_at = now` → 401; future `expires_at` authenticates; unexpired rotated key authenticates during grace and 401s after.
5. **Rotation REST:** returns the new secret once (idempotent replay returns the stored response with the same secret); calling key works during grace, 401 after grace; old key's `expires_at` respects `least()` when its existing expiry is nearer than grace; `expiresIn` on mint stores `now + duration`; malformed/non-positive `expiresIn` → 400.
6. **`last_used_at`:** a successful authenticated call stamps it; a 401 does not; a stamp failure does not fail the request (fault-injected).
7. **Body cap:** `Content-Length` over max → 413 without reading; chunked body over max (no Content-Length) → 413 via the bounded read; at-max body passes; the 413 leaves no idempotency row.

## Non-goals / deliberate bounds

- **Single-process limiter state:** a restart resets buckets (full burst quota after boot); a second instance enforces independently — the same single-process stance as the delivery worker and retention prune; scale-out needs shared state (`SKIP LOCKED`-style claiming or a shared store).
- **No per-IP throttling; unauthenticated routes unthrottled** (the simulator is open by design).
- **No per-merchant limit overrides** — global properties only; the override table stays backlog.
- **Idle buckets never evicted** — bounded by tenant count.
- **`last_used_at` is a write per request** — the throttled async flush stays deferred.
- **The cap guards the buffered merchant-write path only** — simulator writes stay uncapped by design.
- **No per-route limit classes** (reads vs writes) — one bucket per tenant; split classes when evidence demands.
