-- M2 accounts: module-owned schema. A payment account wraps exactly one
-- backing ledger account, referenced by public UUID only — internal ids
-- never cross modules. Unlike the journal, this table legitimately mutates
-- (status transitions), so there are no immutability triggers here.

create schema accounts;

create table accounts.payment_account (
  id                        bigint generated always as identity primary key,
  public_id                 uuid not null default gen_random_uuid() unique,
  holder_name               text not null check (holder_name <> ''),
  status                    text not null default 'ACTIVE'
                            check (status in ('ACTIVE', 'FROZEN', 'CLOSED')),
  ledger_account_public_id  uuid not null unique,
  opened_at                 timestamptz not null default now(),
  closed_at                 timestamptz
);

create index payment_account_holder_idx on accounts.payment_account (holder_name);

-- Same least-privilege pattern as V3, applied to this module's schema.
grant usage on schema accounts to nummus_app;
grant select, insert on accounts.payment_account to nummus_app;
grant update (status, closed_at) on accounts.payment_account to nummus_app;
grant usage on all sequences in schema accounts to nummus_app;
