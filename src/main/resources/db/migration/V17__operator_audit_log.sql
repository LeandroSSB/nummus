create schema audit;

create table audit.operator_action (
  id           bigint generated always as identity primary key,
  public_id    uuid not null default gen_random_uuid() unique,
  actor_key    uuid not null references merchants.operator_key(public_id),
  action       text not null,
  subject_type text not null,
  subject_id   uuid,
  detail       jsonb not null default '{}'::jsonb,
  occurred_at  timestamptz not null default now()
);
create index operator_action_action_idx on audit.operator_action (action, id desc);

grant usage on schema audit to nummus_app;
grant select, insert on audit.operator_action to nummus_app;
