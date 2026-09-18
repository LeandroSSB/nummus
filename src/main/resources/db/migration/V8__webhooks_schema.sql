-- M5 webhooks: transactional outbox for merchant event delivery. Events and
-- their per-endpoint delivery rows are written in the SAME transaction as the
-- state change that produced them (fan-out at write time — subscription
-- semantics are exact). Delivery is at-least-once with bounded retries.
-- Endpoints soft-delete (status DELETED) so delivery history survives; hence
-- no delete grant.

create schema webhooks;

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
  id                   bigint generated always as identity primary key,
  event_id             bigint not null references webhooks.webhook_event(id),
  endpoint_id          bigint not null references webhooks.webhook_endpoint(id),
  status               text not null default 'PENDING'
                       check (status in ('PENDING','SUCCEEDED','FAILED')),
  attempts             int not null default 0,
  next_attempt_at      timestamptz not null default now(),
  last_attempt_at      timestamptz,
  last_response_status int,
  unique (event_id, endpoint_id)
);

create index webhook_delivery_due_idx
  on webhooks.webhook_delivery (status, next_attempt_at);

grant usage on schema webhooks to nummus_app;
grant select, insert, update on webhooks.webhook_endpoint,
  webhooks.webhook_event, webhooks.webhook_delivery to nummus_app;
