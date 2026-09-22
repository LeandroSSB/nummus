-- M14 audit attribution: operator keys gain an immutable identity label
-- (existing keys backfill as 'system'), and fee-schedule changes become
-- attributed, append-only history. The merchant's fee_rate/fee_fixed stay
-- the cached current value; each change appends one entry naming the acting
-- operator key. created_by is nullable: NULL is the pre-attribution
-- sentinel for state whose actor predates identity — rendered 'system'.

alter table merchants.operator_key
  add column label text not null default 'system';

create table merchants.fee_schedule_entry (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  merchant_id bigint not null references merchants.merchant(id),
  rate        numeric(9,6) not null,
  fixed       numeric(19,4) not null,
  valid_from  timestamptz not null default now(),
  created_by  uuid references merchants.operator_key(public_id),
  created_at  timestamptz not null default now()
);
create index fee_schedule_entry_merchant_idx
  on merchants.fee_schedule_entry (merchant_id, id desc);

insert into merchants.fee_schedule_entry (merchant_id, rate, fixed, created_by)
select id, fee_rate, fee_fixed, null from merchants.merchant;

grant select, insert on merchants.fee_schedule_entry to nummus_app;
