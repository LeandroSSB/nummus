# M5 — Webhooks via transactional outbox design

**Date:** 2026-09-18 · **Status:** approved in brainstorming
**Builds on:** M1 ledger core, M2 accounts + REST, M3 payment intents + PSP simulator, M4 idempotency layer

## Goal

Notify merchants of payment-intent lifecycle changes with delivery guarantees a
payments core owes: events are written to an outbox in the same transaction as
the state change (never lost, never phantom), and a delivery worker delivers
them at-least-once to registered HTTPS endpoints, signed, with exponential
backoff and a bounded retry budget.

## Decisions (locked in brainstorming)

1. **Event catalog: intent lifecycle only.** `payment_intent.settled`,
   `payment_intent.failed`, `payment_intent.expired` — the state changes the
   M3 spec already anticipated webhooks for. `created` is excluded (the
   synchronous 201 response already delivers it). Account lifecycle events
   wait for the milestone that needs them.
2. **Subscription management via REST.** `POST /v1/webhook-endpoints`
   registers a URL with a server-generated signing secret and an optional
   event-type filter. No merchant identity exists yet, so endpoints are
   global; per-merchant scoping arrives via migration when authentication
   lands (the same deferral M4 recorded for idempotency keys).
3. **Stripe-style signatures.** Deliveries carry
   `Nummus-Signature: t=<epoch-seconds>,v1=<hex hmac-sha256(secret, t + "." + body)>`
   so receivers can reject replays outside a tolerance window. The secret is
   returned exactly once, in the create response.
4. **In-process scheduled worker with bounded exponential backoff.** A
   `@Scheduled` worker (the purge-job pattern) polls due deliveries, batch of
   50, delivering in id order: 2xx → `SUCCEEDED`; otherwise `attempts + 1`
   and `next_attempt_at = now + backoff-base × 2^attempts` (exponent taken
   after the increment, so a PT2S base schedules 4s, 8s, … 256s); after 8
   attempts the
   delivery is `FAILED` permanently. Delivery attempts are recorded and
   queryable over REST.
5. **Producer-owned port, write-time fan-out.** `payments.application` defines
   `IntentLifecycleEvent` and the `IntentLifecycleEvents` port; the `webhooks`
   module implements it (mirroring the M3 inversion where the simulator
   implements `PaymentNetwork`). The adapter serializes the payload once and
   inserts the event plus one `webhook_delivery` row per **active endpoint
   subscribed to the event's type at publish time** — all inside the caller's
   transaction. Subscription semantics are therefore exact: an endpoint
   registered after the event never receives it. Rejected alternatives:
   relay-time fan-out (a single-row outbox polled and fanned out later —
   subscription cutoff happens at relay time, the wrong instant for a
   payments product) and Spring `@TransactionalEventListener` (AFTER_COMMIT
   loses events on crash between commit and listener; the guarantee the
   transactional outbox exists for is gone).

## Architecture

```
PaymentsServiceImpl (settle / fail / lazy-expire)     one transaction
  ├─ guarded transition (M3)
  └─ intentEvents.publish(IntentLifecycleEvent) ──┐  joins via REQUIRED
                                                 ▼
  webhooks.infrastructure.OutboxIntentLifecycleEvents
    ├─ serialize envelope once (ObjectMapper)
    ├─ INSERT webhook_event (type, payload, occurred_at)
    └─ INSERT webhook_delivery per ACTIVE endpoint whose
       event_types matches (jsonb `[]` = all types)

WebhookDeliveryWorker  @Scheduled(fixedDelay 1s)   separate transactions
  └─ claim due (PENDING, next_attempt_at <= now, id order, batch 50,
      joined with its endpoint's url/secret/status) → for each:
        endpoint no longer ACTIVE   → FAILED without an HTTP attempt
        EventDeliveryClient.deliver(url, headers, body)
          ├─ 2xx                    → SUCCEEDED
          └─ non-2xx / IO / timeout → attempts+1, backoff or FAILED
```

Crash windows: the event and its deliveries commit with the state change or
not at all — a settled intent without its outbox rows is impossible, and an
outbox row for an uncommitted settlement cannot exist. Delivery is
at-least-once (a crash after the HTTP call but before recording the outcome
re-delivers); receivers must tolerate duplicates — the signed envelope's `id`
makes deduplication trivial.

The worker is single-process (the modular monolith deploys as one instance);
`fixedDelay` guarantees no self-overlap. Horizontal scale-out needs claiming
with `FOR UPDATE SKIP LOCKED` — recorded in the backlog, not built here.

### Event envelope

```json
{
  "id": "0f4c…",                      // event public id (uuid)
  "type": "payment_intent.settled",
  "occurredAt": "2026-09-18T21:14:03Z",
  "data": {
    "publicId": "…", "accountId": "…",
    "amount": "20.0000", "currency": "BRL",
    "status": "SETTLED", "chargeId": "…",
    "settledAt": "…", "journalTransactionId": "…"
  }
}
```

Serialized once at publish; every delivery sends the exact stored bytes.
`amount` is a string (money never travels as a JSON number).

## Components

```
payments/application/
  IntentLifecycleEvent.java        record: type, publicId, accountPublicId,
                                   Money amount, status, chargePublicId,
                                   settledAt, journalTransactionPublicId
  IntentLifecycleEvents.java       port: void publish(IntentLifecycleEvent)

webhooks/
  domain/       WebhookEndpoint, WebhookEvent, Delivery,
                UnknownWebhookEndpointException
  application/  WebhookEndpointsService(+Impl), WebhookDeliveryWorker,
                WebhookStore (port), EventDeliveryClient (port),
                SignatureHeaders, WebhookProperties
  infrastructure/
                JdbcClientWebhookStore,
                RestClientEventDeliveryClient   (connect 2s, read 5s)
                OutboxIntentLifecycleEvents     (implements the payments port)
  interfaces/   WebhookEndpointsController,
                WebhookDeliveriesController     (GET …/{id}/deliveries)
                dto/                            CreateEndpointRequest,
                                                   EndpointResponse,
                                                   CreateEndpointResponse,
                                                   DeliveryResponse
```

- `SignatureHeaders` — pure: `(secret, bodyBytes, now) → "t=…,v1=…"`,
  HMAC-SHA256 over `t + "." + body`, hex-encoded.
- `EventDeliveryClient` — port isolating HTTP so the worker is testable;
  POSTs the exact stored payload bytes with `Content-Type: application/json`,
  `Nummus-Signature`, and `Nummus-Event: <type>` headers; returns
  `(delivered: boolean, httpStatus: Integer?)`.
- `WebhookProperties` — `nummus.webhooks.max-attempts` (8),
  `backoff-base` (PT2S), `batch-size` (50).
- `PaymentsServiceImpl` publishes after each guarded transition succeeds —
  a lost race publishes nothing.

## REST contract

| Endpoint | Behavior |
| --- | --- |
| `POST /v1/webhook-endpoints` | `@Idempotent` (M4 layer: key required, replay on retry). Body `{url, eventTypes?}`. Validates URL `http(s)://` and `eventTypes ⊆ catalog` → 400. **201** returns `{publicId, url, eventTypes, status, createdAt, secret}` — `secret` appears here and nowhere else. |
| `GET /v1/webhook-endpoints` | Active endpoints; never the secret. |
| `GET /v1/webhook-endpoints/{id}` | 404 for unknown **or deleted**. |
| `DELETE /v1/webhook-endpoints/{id}` | Soft delete → status `DELETED`; delivery history preserved; pending deliveries for it fail without an HTTP attempt. |
| `GET /v1/webhook-endpoints/{id}/deliveries` | Attempt log: event public id + type, status, attempts, `lastResponseStatus`, `nextAttemptAt`; optional `status` filter. |

## Persistence — `V8__webhooks_schema.sql`

Schema `webhooks` (module-owned, single Flyway chain):

```sql
create table webhooks.webhook_endpoint (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  url         text not null check (url ~ '^https?://'),
  secret      text not null,
  event_types jsonb not null default '[]'::jsonb,
  status      text not null default 'ACTIVE' check (status in ('ACTIVE','DELETED')),
  created_at  timestamptz not null default now()
);

create table webhooks.webhook_event (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  type        text not null,
  payload     text not null,
  occurred_at timestamptz not null,
  created_at  timestamptz not null default now()
);

create table webhooks.webhook_delivery (
  id                  bigint generated always as identity primary key,
  event_id            bigint not null references webhooks.webhook_event(id),
  endpoint_id         bigint not null references webhooks.webhook_endpoint(id),
  status              text not null default 'PENDING'
                      check (status in ('PENDING','SUCCEEDED','FAILED')),
  attempts            int not null default 0,
  next_attempt_at     timestamptz not null default now(),
  last_attempt_at     timestamptz,
  last_response_status int,
  unique (event_id, endpoint_id)
);

create index webhook_delivery_due_idx
  on webhooks.webhook_delivery (status, next_attempt_at);

grant usage on schema webhooks to nummus_app;
grant select, insert, update on webhooks.webhook_endpoint,
  webhooks.webhook_event, webhooks.webhook_delivery to nummus_app;
```

`event_types` semantics: `[]` (default) = all event types; otherwise a JSON
array of type strings. No `delete` grant — deletion is a status transition;
pruning old deliveries is backlog work.

## Error vocabulary (GlobalExceptionHandler additions)

- `UnknownWebhookEndpointException` → 404 (unknown and deleted alike).
- Invalid URL / unknown event type → 400 via bean validation and the
  existing `badRequest` path.

## Testing

- **Schema (`WebhooksSchemaTest`)**: V8 shape, checks, FKs,
  `unique(event_id, endpoint_id)`, grants under `nummus_app` (the M4-review
  lesson: roles are exercised, not assumed).
- **Store (`WebhookStoreTest`)**: endpoint CRUD with soft delete; event
  insert fans out only to ACTIVE endpoints subscribed to the type (`[]` =
  all); claim-due respects `next_attempt_at`, status, and id order; outcome
  recording (success / retry with backoff / permanent failure).
- **Signature (`SignatureHeadersTest`)**: pure unit — deterministic HMAC
  vector, `t` propagated into the MAC input.
- **REST (`WebhookEndpointsRestApiTest`)**: create 201 + secret exactly once
  (list/get never return it; an idempotent retry replays the stored 201 with
  the secret — replay is byte-exact storage by design); 400 on bad URL and
  unknown event type; 404 unknown/deleted; delete then 404; deliveries
  listing.
- **Transactional publish (`WebhookPublishTest`)**: settling an intent (via
  the simulator path) writes exactly one `webhook_event` of type
  `payment_intent.settled` and one delivery per matching endpoint; a
  settlement that rolls back (frozen account settle race) writes nothing;
  failed and expired intents publish their types.
- **Worker (`WebhookDeliveryWorkerTest`)**: receiver is a JDK
  `com.sun.net.httpserver.HttpServer` on an ephemeral port. 2xx →
  `SUCCEEDED`, receiver-verified HMAC and byte-exact payload; 500 → attempt
  counted, backoff scheduled; exhausted budget → `FAILED`; deleted endpoint
  → `FAILED` without an HTTP request.
- **End-to-end**: register endpoint → create intent → simulator pays →
  worker delivers → receiver asserts a valid signature over the settled
  envelope.

## Out of scope (recorded to prevent re-litigation)

- Merchant authentication and per-merchant endpoint scoping (migration adds
  the merchant column when auth lands — same deferral as idempotency keys).
- Account-lifecycle events, `payment_intent.created`, and event replay/redrive
  APIs (manual retry of failed deliveries).
- Multi-instance workers (`FOR UPDATE SKIP LOCKED` claiming), delivery
  pruning/retention, dead-letter notifications beyond the `FAILED` status,
  and webhook endpoint health checks (Stripe-style ping).
- The M4-review backlog items (two-clock expiry, ArchUnit gaps, body caps) —
  untouched here except where the new ArchUnit rule for `webhooks` lands
  alongside existing rules.
