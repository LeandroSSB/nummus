-- M1 ledger core: module-owned schema. Currency lives only on the account,
-- making the whole journal BRL by construction until multi-currency arrives.

create schema ledger;

create table ledger.ledger_account (
  id         bigint generated always as identity primary key,
  public_id  uuid not null default gen_random_uuid() unique,
  name       text not null,
  type       text not null check (type in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
  currency   char(3) not null default 'BRL' check (currency = 'BRL'),
  status     text not null default 'ACTIVE' check (status in ('ACTIVE', 'FROZEN', 'CLOSED')),
  opened_at  timestamptz not null default now(),
  closed_at  timestamptz
);

create table ledger.journal_transaction (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  memo        text,
  booked_at   timestamptz not null default now(),
  reversal_of bigint references ledger.journal_transaction(id),
  unique (reversal_of)
);

create table ledger.journal_posting (
  id             bigint generated always as identity primary key,
  transaction_id bigint not null references ledger.journal_transaction(id),
  account_id     bigint not null references ledger.ledger_account(id),
  direction      text not null check (direction in ('DEBIT', 'CREDIT')),
  amount         numeric(19,4) not null check (amount > 0)
);

create index journal_posting_account_idx
  on ledger.journal_posting (account_id) include (direction, amount);

create index journal_posting_transaction_idx
  on ledger.journal_posting (transaction_id);
