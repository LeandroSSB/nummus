-- M3 psp-simulator: module-owned schema playing the external payment network.
-- Charges persist like a real network's state would — this is what M6 conciliation
-- will match settlement reports against.

create schema psp_simulator;

create table psp_simulator.charge (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  amount      numeric(19,4) not null check (amount > 0),
  status      text not null default 'PENDING'
              check (status in ('PENDING','SUCCEEDED','FAILED')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

grant usage on schema psp_simulator to nummus_app;
grant select, insert on psp_simulator.charge to nummus_app;
grant update (status, updated_at) on psp_simulator.charge to nummus_app;
grant usage on all sequences in schema psp_simulator to nummus_app;
