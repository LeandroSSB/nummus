-- M6 conciliation: settlement reports are write-once audit artifacts. The
-- report, its lines, and the match verdicts are computed and inserted in one
-- transaction at ingest; corrections are new reports, never updates — hence
-- no update or delete grant for the application role.

create schema conciliation;

create table conciliation.settlement_report (
  id                       bigint generated always as identity primary key,
  public_id                uuid not null default gen_random_uuid() unique,
  period_from              timestamptz not null,
  period_to                timestamptz not null,
  status                   text not null check (status in ('OPEN','CONCILED')),
  matched_count            int not null,
  amount_mismatched_count  int not null,
  missing_internal_count   int not null,
  missing_external_count   int not null,
  created_at               timestamptz not null default now()
);

create table conciliation.report_line (
  id                        bigint generated always as identity primary key,
  report_id                 bigint not null references conciliation.settlement_report(id),
  origin                    text not null check (origin in ('EXTERNAL','INTERNAL')),
  charge_public_id          uuid not null,
  reported_amount           numeric(19,4),
  internal_intent_public_id uuid,
  internal_amount           numeric(19,4),
  match_status              text not null check (match_status in
                            ('MATCHED','AMOUNT_MISMATCH','MISSING_INTERNAL','MISSING_EXTERNAL')),
  unique (report_id, charge_public_id)
);

grant usage on schema conciliation to nummus_app;
grant select, insert on conciliation.settlement_report, conciliation.report_line
  to nummus_app;
