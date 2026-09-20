-- M10 webhook hardening: deliveries gain the public identifier every
-- user-facing row carries (the redrive route and the pagination cursor
-- address it; existing rows backfill via the default), and the retention
-- job needs its delete grant. V8 deliberately granted no delete here —
-- endpoints soft-delete; succeeded deliveries now age out under the
-- retention policy instead.

alter table webhooks.webhook_delivery
  add column public_id uuid not null default gen_random_uuid() unique;

grant delete on webhooks.webhook_delivery to nummus_app;
