-- M16 payouts: money-out closes the product loop. The payments module gains
-- the payout row (request/execute/return journal links, status-guarded like
-- payment_intent); the simulator gains outbound transfers (the charge
-- table's mirror); merchants carry a fixed payout fee joining the M9
-- schedule and the M14 attributed history; the ledger gains the pooled
-- payout-reserve liability the two-phase reservation posts through.
-- Migration-seeded ledger data follows the V5/V12 precedent: deployment
-- data, never runtime cross-module writes.

create table payments.payout (
  id                            bigint generated always as identity primary key,
  public_id                     uuid not null default gen_random_uuid() unique,
  account_public_id             uuid not null,
  amount                        numeric(19,4) not null check (amount > 0),
  destination_bank_key          text not null
                                check (char_length(destination_bank_key) between 1 and 64),
  status                        text not null default 'REQUESTED'
                                check (status in ('REQUESTED','SETTLED','FAILED','EXPIRED')),
  transfer_public_id            uuid not null unique,
  fee_amount                    numeric(19,4),
  expires_at                    timestamptz not null,
  created_at                    timestamptz not null default now(),
  settled_at                    timestamptz,
  request_transaction_public_id uuid not null,
  execute_transaction_public_id uuid unique,
  return_transaction_public_id  uuid unique
);

create index payout_account_idx on payments.payout (account_public_id, id desc);

grant select, insert on payments.payout to nummus_app;
grant update (status, settled_at, fee_amount, execute_transaction_public_id,
  return_transaction_public_id) on payments.payout to nummus_app;
grant usage on all sequences in schema payments to nummus_app;

create table psp_simulator.payout_transfer (
  id                   bigint generated always as identity primary key,
  public_id            uuid not null default gen_random_uuid() unique,
  amount               numeric(19,4) not null check (amount > 0),
  destination_bank_key text not null,
  status               text not null default 'PENDING'
                       check (status in ('PENDING','SUCCEEDED','FAILED')),
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now()
);

grant select, insert on psp_simulator.payout_transfer to nummus_app;
grant update (status, updated_at) on psp_simulator.payout_transfer to nummus_app;
grant usage on all sequences in schema psp_simulator to nummus_app;

alter table merchants.merchant
  add column payout_fee_fixed numeric(19,4) not null default 0
    check (payout_fee_fixed >= 0);

alter table merchants.fee_schedule_entry
  add column payout_fixed numeric(19,4) not null default 0;

insert into ledger.ledger_account (public_id, name, type)
values ('5f9c3b2e-0000-4000-8000-000000000003', 'payout reserve', 'LIABILITY');
