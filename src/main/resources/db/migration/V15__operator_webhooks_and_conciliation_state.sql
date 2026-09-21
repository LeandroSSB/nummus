-- M12 conciliation automation: the operator webhook namespace and the
-- scheduled-ingest window state. Operator endpoints are webhook_endpoint
-- rows with merchant_public_id NULL -- the same namespace trick
-- idempotency_keys has used since M7 -- so one delivery worker, one URL
-- policy, and one retention job serve both audiences. The seed default on
-- merchant_public_id was V10 migration-era backfill; both registration
-- paths now pass explicit values, so the default goes away.

alter table webhooks.webhook_endpoint
  alter column merchant_public_id drop not null,
  alter column merchant_public_id drop default;

create table conciliation.ingest_state (
  id              int primary key check (id = 1),
  last_window_end timestamptz not null,
  updated_at      timestamptz not null default now()
);

insert into conciliation.ingest_state (id, last_window_end) values (1, now());

grant select, insert, update on conciliation.ingest_state to nummus_app;
