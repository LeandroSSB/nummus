-- M9 fees: the per-merchant fee schedule (fraction of gross plus a fixed BRL
-- amount), the settle-time fee fact on payment intents, and the system
-- REVENUE account the fee leg credits. The ledger seed follows the V5
-- clearing precedent: migration-seeded deployment data, never runtime
-- cross-module writes. merchants.merchant needs no new grant — V10's
-- table-level update grant covers added columns; payment_intent's V5
-- update grant is column-scoped, so fee_amount is granted here.

alter table merchants.merchant
  add column fee_rate  numeric(9,6)  not null default 0
    check (fee_rate >= 0 and fee_rate < 1),
  add column fee_fixed numeric(19,4) not null default 0
    check (fee_fixed >= 0);

alter table payments.payment_intent
  add column fee_amount numeric(19,4);

grant update (fee_amount) on payments.payment_intent to nummus_app;

insert into ledger.ledger_account (public_id, name, type)
values ('5f9c3b2e-0000-4000-8000-000000000002', 'payment fees', 'REVENUE');
