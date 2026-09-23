-- M18 expiry resolution: CANCELLED joins the instruction statuses so an
-- expiring hold can resolve its network instruction instead of abandoning
-- it. Constraints are swapped (checks cannot be altered in place); grants
-- already cover the columns. Charges never cancel — money-in has no expiry
-- abandonment.

alter table psp_simulator.payout_transfer
  drop constraint if exists payout_transfer_status_check;
alter table psp_simulator.payout_transfer
  add constraint payout_transfer_status_check
  check (status in ('PENDING','SUCCEEDED','FAILED','CANCELLED'));

alter table psp_simulator.charge_refund
  drop constraint if exists charge_refund_status_check;
alter table psp_simulator.charge_refund
  add constraint charge_refund_status_check
  check (status in ('PENDING','SUCCEEDED','FAILED','CANCELLED'));
