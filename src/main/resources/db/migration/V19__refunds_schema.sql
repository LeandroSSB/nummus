-- M17 refunds: settled money returns. The payments module gains the refund
-- row (payout's sibling, hold/execute/return journal links); the simulator
-- gains per-charge refunds enforcing its own never-over-refund invariant;
-- the ledger gains the pooled refund-reserve liability the two-phase hold
-- posts through. Migration-seeded ledger data per the V5/V12/V18 precedent.

create table payments.refund (
  id                            bigint generated always as identity primary key,
  public_id                     uuid not null default gen_random_uuid() unique,
  intent_public_id              uuid not null,
  amount                        numeric(19,4) not null check (amount > 0),
  status                        text not null default 'REQUESTED'
                                check (status in ('REQUESTED','SETTLED','FAILED','EXPIRED')),
  network_refund_public_id      uuid not null unique,
  expires_at                    timestamptz not null,
  created_at                    timestamptz not null default now(),
  settled_at                    timestamptz,
  hold_transaction_public_id    uuid not null,
  execute_transaction_public_id uuid unique,
  return_transaction_public_id  uuid unique
);

create index refund_intent_idx on payments.refund (intent_public_id, id desc);

grant select, insert on payments.refund to nummus_app;
grant update (status, settled_at, execute_transaction_public_id,
  return_transaction_public_id) on payments.refund to nummus_app;
grant usage on all sequences in schema payments to nummus_app;

create table psp_simulator.charge_refund (
  id                bigint generated always as identity primary key,
  public_id         uuid not null default gen_random_uuid() unique,
  charge_public_id  uuid not null,
  amount            numeric(19,4) not null check (amount > 0),
  status            text not null default 'PENDING'
                    check (status in ('PENDING','SUCCEEDED','FAILED')),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

grant select, insert on psp_simulator.charge_refund to nummus_app;
grant update (status, updated_at) on psp_simulator.charge_refund to nummus_app;
grant usage on all sequences in schema psp_simulator to nummus_app;

insert into ledger.ledger_account (public_id, name, type)
values ('5f9c3b2e-0000-4000-8000-000000000004', 'refund reserve', 'LIABILITY');
