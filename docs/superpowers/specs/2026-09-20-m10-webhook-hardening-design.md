# M10 — Webhook Hardening

**Date:** 2026-09-20
**Status:** approved design, pending implementation plan

## Goal

Make webhook delivery production-grade: registration and delivery reject server-side-request-forgery targets, merchants can requeue their own failed deliveries, succeeded deliveries age out under a retention policy, and the deliveries listing paginates without breaking its existing array contract.

## Product decisions (locked)

1. **Scheme policy:** `https` is required — except plain `http` whose host resolves exclusively to loopback, which stays registrable so local receivers (development, test harness) keep working.
2. **SSRF is enforced in two layers:** at registration (fast 400) and again before every delivery attempt (closes DNS rebinding). Redirects are never followed; any 3xx is a failed attempt.
3. **Redrive is merchant self-serve:** the endpoint owner requeues a `FAILED` delivery; `attempts` resets to zero and the backoff schedule restarts. `@Idempotent`.
4. **Retention prunes `SUCCEEDED` only:** deliveries older than the TTL (default 30 days, configurable, `0` disables) are deleted in batches by a scheduled job in the delivery worker's discipline. `FAILED` and `PENDING` deliveries are never pruned; webhook events (payloads) accumulate — a documented bound.
5. **Pagination is non-breaking:** the response body stays a JSON array; the cursor travels in a `Next-Cursor` response header, present only when more pages exist.

## URL policy

`webhooks.application.WebhookUrlPolicy` — a pure checker with one entry point:

```java
void check(URI url) throws UnsafeWebhookUrlException
```

Rejects when any of the following holds:

- the scheme is not `https`, unless it is `http` AND every address the host resolves to is loopback;
- the URL carries userinfo (`scheme://user:pass@host/...`);
- any resolved address (A/AAAA, via `InetAddress.getAllByName`) is loopback (v4/v6), link-local (v4 `169.254.0.0/16` — which covers the cloud metadata address — or v6 `fe80::/10`), site-local/private (`10/8`, `172.16/12`, `192.168/16`), IPv6 unique-local (`fc00::/7`), any-local (`0.0.0.0`/`::`), or multicast;
- the host does not resolve at all.

Loopback is the only tolerated internal class, and only over `http`. Literal-IP hosts (no DNS) follow the same address rules. Port is unrestricted (documented). DNS resolution is trusted as-is per call — no pinning of registration-time answers (that is what the delivery-time recheck is for).

**Enforcement points:**

- **Registration** (`WebhookEndpointsService.register`): policy violation → `UnsafeWebhookUrlException` → 400 problem+json. The endpoint row is never written for a rejected URL.
- **Delivery** (`WebhookDeliveryWorker`, before each POST): violation → the attempt records a failure (`DeliveryResult(false, null)` — the same shape as a connect failure), so the existing backoff/retry/terminal-FAILED machinery applies unchanged.
- **Redirects:** the delivery client performs no redirect following; a 3xx response is simply not 2xx and fails the attempt. Pinned by test.

## Migration

`V13__webhook_delivery_public_id_and_delete_grant.sql` — the only schema change. Deliveries gain the public identifier every user-facing row carries (today they have none — the listing exposes only the event id, which cannot key a redrive or a cursor), and the retention job needs its delete grant:

```sql
alter table webhooks.webhook_delivery
  add column public_id uuid not null default gen_random_uuid() unique;

grant delete on webhooks.webhook_delivery to nummus_app;
```

Existing rows backfill via the default. `DeliveryResponse` gains an additive `deliveryId` field; the cursor and the redrive route address deliveries by it. Everything else runs against existing columns (`status`, `attempts`, `next_attempt_at`, `last_attempt_at`).

## Redrive

- Route: `POST /v1/webhook-deliveries/{deliveryId}/redrive` — merchant-authenticated, `@Idempotent` (merchant namespace). `202 Accepted`.
- Store operation (single guarded statement):
  `update webhooks.webhook_delivery d set status='PENDING', attempts=0, next_attempt_at=now() from webhooks.webhook_endpoint e where d.endpoint_id = e.id and d.public_id = :deliveryId and e.merchant_public_id = :merchantId and d.status='FAILED'`
- Affected row `!= 1` → `UnknownWebhookDeliveryException` (`webhooks.domain`, → 404 via advice): the delivery is unknown, belongs to another merchant, or is not `FAILED`. Deliberately indistinguishable, mirroring the endpoint vocabulary.
- Semantics: `attempts` restarts at zero, so `maxAttempts` grants a full fresh retry cycle; the payload delivered is the exact stored event payload (unchanged outbox bytes, same signature scheme).
- A redriven delivery whose endpoint was since soft-deleted is picked up by the worker under its existing rules (deleted endpoints are skipped — the worker's semantics govern).

## Retention

- `WebhookProperties` gains `retentionDays` (`@DefaultValue("30")`; `0` disables pruning).
- `WebhookRetentionWorker`: scheduled with the same single-process `fixedDelay` discipline as the delivery worker; each tick calls `store.pruneSucceededBefore(now minus retentionDays)` which deletes in `batchSize` batches:
  `delete from webhooks.webhook_delivery where id in (select id from webhooks.webhook_delivery where status='SUCCEEDED' and last_attempt_at < :cutoff limit :batch)` — looped until a batch comes back short.
- Only `SUCCEEDED` rows with `last_attempt_at` (the completion proxy) older than the cutoff are deleted. `FAILED` and `PENDING` never. Events are not pruned (each event is one row versus N delivery rows — the multiplier is deliveries; event pruning stays a documented bound).
- A second instance racing the prune would issue idempotent deletes — harmless, same stance as the delivery worker's documented single-process bound.

## Pagination

- `GET /v1/webhook-endpoints/{id}/deliveries?status=&after=&limit=` — `status` unchanged; `limit` defaults to 50, capped at 100 (`limit < 1` or `> 100` → 400 problem+json); `after` is the opaque cursor (a delivery public id).
- Keyset: `... and id < (select id from webhooks.webhook_delivery where public_id = :after) order by id desc limit :limit` — the primary key indexes the walk.
- The body remains a JSON array of delivery objects (existing contract). The store fetches `limit + 1` rows; when the extra row exists, the response carries header `Next-Cursor: <last delivery public id of the returned page>`; otherwise (short page, or exactly `limit` with nothing beyond) the header is absent.
- An `after` cursor that does not resolve (unknown or pruned) yields an empty page with no cursor — silent, Stripe-style; the walk terminates. A foreign-but-real cursor resolves and acts as an opaque skip over the caller's own rows — no cross-tenant data is ever returned (the listing stays merchant-scoped). Cursors are opaque bookmarks. Documented behavior, not an error.

## Error handling

- `UnsafeWebhookUrlException` (`webhooks.application`) → 400 problem+json, message names the violated rule.
- `UnknownWebhookDeliveryException` (`webhooks.domain`) → 404 problem+json.
- `limit`/`after` malformed → 400 via bean-validation-style parameter checking matching house conventions.
- Delivery-time policy violations are NOT errors to any caller — they are failed attempts inside the existing retry model.

## Security and invariants

- Registration is merchant-authenticated (existing); the policy narrows what a merchant can make the platform dial. Delivery-time revalidation narrows what a changed DNS record can.
- No redirect-following anywhere in the delivery path.
- Redrive is scoped by merchant ownership at the SQL level (join), never by a read-then-write race.
- Pruning grants and runs `DELETE` on `webhook_delivery` only; events and endpoints keep their existing grants (`V8` deliberately granted no delete there — soft-delete semantics unchanged).

## Testing

1. **Policy (pure unit):** https-to-public passes; http-to-loopback-literal passes (v4 and v6); http-to-`localhost` passes (resolves loopback); http-to-private-literal rejected; https-to-private-literal rejected; link-local rejected (v4 metadata address and v6); unique-local rejected; any-local rejected; multicast rejected; userinfo rejected; unresolvable host rejected; non-http(s) scheme rejected.
2. **Registration REST:** private-literal URL → 400 with problem+json; loopback http → 201 (the existing suites registering `http://localhost` receivers are the standing regression proof of the exemption).
3. **Delivery-time revalidation:** an endpoint row with a policy-violating URL inserted directly via SQL (bypassing the REST guard, as an attacker's DNS change would bypass registration) with a due `PENDING` delivery → worker tick records a failed attempt and applies backoff.
4. **Redirects:** a receiver answering 302 to the signed POST → attempt fails (non-2xx), no follow.
5. **Redrive REST:** FAILED → 202 and the row is `PENDING` with `attempts = 0`; idempotent replay returns the stored 202; foreign-merchant delivery → 404; `SUCCEEDED`/`PENDING` delivery → 404.
6. **Retention:** aged `SUCCEEDED` rows pruned (insert aged rows via SQL, run the prune, assert); aged `FAILED` rows survive; `retentionDays = 0` disables; roles test asserts `nummus_app` holds `DELETE` on `webhook_delivery` (V13).
7. **Pagination:** more than `limit` deliveries → first page carries `Next-Cursor`, walk completes with a final short page and no cursor; `after` + `status` combine; unknown cursor → empty page without cursor; `limit` 0 and 101 → 400.

## Non-goals / deliberate bounds

- **Events are never pruned** — payload rows accumulate; revisit when volume makes it matter.
- **No per-endpoint allowlist or IP pinning** — the two-layer check is the whole stance; pinning registration-time DNS answers would break legitimate CDN DNS churn.
- **No mTLS, custom CA, or retry-budget per endpoint** — out of scope.
- **Operator-level redrive or delivery inspection** stays out (operators remain role-level, M8 bound).
- **Pruning is single-process** like the delivery worker; scale-out needs the same `SKIP LOCKED` treatment already documented for delivery.
