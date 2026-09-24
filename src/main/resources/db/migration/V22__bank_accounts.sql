-- M20 bank-account registry: payout destinations become registered,
-- merchant-scoped, verified bank accounts. The table stores the structured
-- Brazilian payout shape plus a SHA-256 verification-code hash (the code is
-- shown once at registration, the API-key precedent). The partial unique
-- keeps one active registration per natural key per merchant; revoked rows
-- free the key. Mutations are status-guarded transitions only — the app
-- role updates just the two status columns, never the identity fields.

create table merchants.bank_account (
  id                    bigint generated always as identity primary key,
  public_id             uuid not null default gen_random_uuid() unique,
  merchant_public_id    uuid not null,
  bank_code             text not null check (bank_code ~ '^\d{3}$'),
  branch                text not null check (branch ~ '^\d{1,5}(-\d)?$'),
  account_number        text not null check (account_number ~ '^[0-9-]{1,20}$'),
  holder_tax_id         text not null check (holder_tax_id ~ '^\d{11}$|^\d{14}$'),
  status                text not null default 'PENDING_VERIFICATION'
                        check (status in ('PENDING_VERIFICATION','VERIFIED','REVOKED')),
  verification_code_hash text not null,
  created_at            timestamptz not null default now(),
  verified_at           timestamptz
);

create unique index bank_account_active_natural_key_uq
  on merchants.bank_account (merchant_public_id, bank_code, branch, account_number)
  where status <> 'REVOKED';
create index bank_account_merchant_idx on merchants.bank_account (merchant_public_id);

grant select, insert on merchants.bank_account to nummus_app;
grant update (status, verified_at) on merchants.bank_account to nummus_app;
