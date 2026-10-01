-- M25 intent void: a merchant may withdraw a CREATED intent before payment.
-- Two check-constraint swaps (the V20 drop/add pattern): the intent state
-- machine gains the distinct terminal VOIDED, and the simulator's charge may
-- now be cancelled (previously terminal-only PENDING/SUCCEEDED/FAILED —
-- money-in had no abandonment). No new tables; the app role's existing
-- column-scoped updates cover the new values.

alter table payments.payment_intent drop constraint payment_intent_status_check;
alter table payments.payment_intent
  add constraint payment_intent_status_check
  check (status in ('CREATED','SETTLED','FAILED','EXPIRED','VOIDED'));

alter table psp_simulator.charge drop constraint charge_status_check;
alter table psp_simulator.charge
  add constraint charge_status_check
  check (status in ('PENDING','SUCCEEDED','FAILED','CANCELLED'));
