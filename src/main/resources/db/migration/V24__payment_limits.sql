-- M23 payment limits: per-merchant caps on single money-moving operations.
-- The fee-schedule storage shape: cached current values on the merchant row,
-- attributed append-only history beside it. NULL cap means unlimited — the
-- default for every existing and new merchant (purely additive).

alter table merchants.merchant
  add column max_intent_amount  numeric(19,4) check (max_intent_amount > 0),
  add column max_payout_amount numeric(19,4) check (max_payout_amount > 0);

create table merchants.payment_limits_entry (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  merchant_id bigint not null references merchants.merchant(id),
  max_intent_amount  numeric(19,4),
  max_payout_amount numeric(19,4),
  valid_from  timestamptz not null default now(),
  created_by  uuid references merchants.operator_key(public_id),
  created_at  timestamptz not null default now()
);
create index payment_limits_entry_merchant_idx
  on merchants.payment_limits_entry (merchant_id, id desc);

grant select, insert on merchants.payment_limits_entry to nummus_app;
