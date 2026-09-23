-- M19 money-out conciliation: report lines key by subject (kind + network
-- instruction id) so payout transfers and charge refunds reconcile beside
-- charges. Existing lines backfill to CHARGE; the V9 grants are table-level
-- and already cover the renamed columns.

alter table conciliation.report_line
  add column subject_type text;
update conciliation.report_line set subject_type = 'CHARGE';
alter table conciliation.report_line
  alter column subject_type set not null;
alter table conciliation.report_line
  add constraint report_line_subject_type_check
  check (subject_type in ('CHARGE','PAYOUT_TRANSFER','CHARGE_REFUND'));

alter table conciliation.report_line
  drop constraint if exists report_line_report_id_charge_public_id_key;
alter table conciliation.report_line
  rename column charge_public_id to subject_public_id;
alter table conciliation.report_line
  rename column internal_intent_public_id to internal_public_id;
alter table conciliation.report_line
  add constraint report_line_unique_subject
  unique (report_id, subject_type, subject_public_id);
