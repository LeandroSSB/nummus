-- M7 merchants: identity and API keys. Merchants own payment accounts,
-- webhook endpoints, and their idempotency-key namespace; existing rows
-- backfill to a seed merchant. api_key stores only the SHA-256 of the
-- secret (shown once at issuance) plus a display prefix.

create schema merchants;

create table merchants.merchant (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  name        text not null,
  created_at  timestamptz not null default now()
);

create table merchants.api_key (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  merchant_id bigint not null references merchants.merchant(id),
  key_hash    text not null unique,
  prefix      text not null,
  status      text not null default 'ACTIVE' check (status in ('ACTIVE','REVOKED')),
  created_at  timestamptz not null default now()
);

grant usage on schema merchants to nummus_app;
grant select, insert, update on merchants.merchant, merchants.api_key to nummus_app;

insert into merchants.merchant (public_id, name)
values ('11111111-1111-4111-8111-111111111111', 'seed merchant');

alter table accounts.payment_account
  add column merchant_public_id uuid not null
  default '11111111-1111-4111-8111-111111111111';
create index payment_account_merchant_idx on accounts.payment_account (merchant_public_id);

alter table webhooks.webhook_endpoint
  add column merchant_public_id uuid not null
  default '11111111-1111-4111-8111-111111111111';
create index webhook_endpoint_merchant_idx on webhooks.webhook_endpoint (merchant_public_id);

alter table idempotency.idempotency_keys
  add column merchant_public_id uuid;
-- V7 declared `key text not null unique` (inline constraint); uniqueness
-- moves to per-namespace partial unique indexes.
alter table idempotency.idempotency_keys
  drop constraint idempotency_keys_key_key;
create unique index idempotency_keys_merchant_key_uq
  on idempotency.idempotency_keys (merchant_public_id, key)
  where merchant_public_id is not null;
create unique index idempotency_keys_operator_key_uq
  on idempotency.idempotency_keys (key)
  where merchant_public_id is null;
