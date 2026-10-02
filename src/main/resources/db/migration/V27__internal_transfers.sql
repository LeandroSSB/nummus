-- M27 internal transfers: instant intra-merchant account-to-account money
-- movement. A transfer is a single-state fact — no status column: the
-- journal transaction link IS the record. merchant_public_id is
-- denormalized (the webhook-tables precedent) so listings scope without the
-- accounts seam.

create table payments.transfer (
  id                            bigint generated always as identity primary key,
  public_id                     uuid not null default gen_random_uuid() unique,
  merchant_public_id            uuid not null,
  from_account_public_id        uuid not null,
  to_account_public_id          uuid not null,
  amount                        numeric(19,4) not null check (amount > 0),
  journal_transaction_public_id uuid not null,
  created_at                    timestamptz not null default now()
);
create index transfer_merchant_idx
  on payments.transfer (merchant_public_id, id desc);

grant select, insert on payments.transfer to nummus_app;
grant usage on all sequences in schema payments to nummus_app;
