-- M3 payments: module-owned schema for payment intents, plus the clearing-asset
-- seed row in the ledger schema. The single Flyway chain is the modular monolith's
-- composition layer: the seed is deployment data, not runtime cross-module access.

create schema payments;

create table payments.payment_intent (
  id                            bigint generated always as identity primary key,
  public_id                     uuid not null default gen_random_uuid() unique,
  account_public_id             uuid not null,
  amount                        numeric(19,4) not null check (amount > 0),
  status                        text not null default 'CREATED'
                                check (status in ('CREATED','SETTLED','FAILED','EXPIRED')),
  charge_public_id              uuid not null unique,
  expires_at                    timestamptz not null,
  created_at                    timestamptz not null default now(),
  settled_at                    timestamptz,
  journal_transaction_public_id uuid unique
);

create index payment_intent_account_idx on payments.payment_intent (account_public_id);

insert into ledger.ledger_account (public_id, name, type)
values ('5f9c3b2e-0000-4000-8000-000000000001', 'psp clearing', 'ASSET');

grant usage on schema payments to nummus_app;
grant select, insert on payments.payment_intent to nummus_app;
grant update (status, settled_at, journal_transaction_public_id) on payments.payment_intent to nummus_app;
grant usage on all sequences in schema payments to nummus_app;
