-- M20 payout integration: the payout row references the registered bank
-- account it paid into. Nullable by design — pre-M20 rows paid raw keys and
-- keep a null reference; the wire key column still carries the derived value
-- for every new row.

alter table payments.payout add column bank_account_public_id uuid;
