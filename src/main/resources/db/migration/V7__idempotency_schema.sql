-- M4 idempotency: stored responses for merchant-facing writes, so retries replay
-- instead of re-executing. Cross-cutting infrastructure owned by the shared
-- interfaces layer (no business module references this table). The response
-- columns are NULL until the owning transaction attaches the serialized
-- response on its way to commit — a committed row always carries its response.

create schema idempotency;

create table idempotency.idempotency_keys (
  id                     uuid primary key default gen_random_uuid(),
  key                    text not null unique,
  request_fingerprint    bytea not null,
  response_status        int,
  response_content_type  text,
  response_location      text,
  response_body          text,
  created_at             timestamptz not null default now(),
  expires_at             timestamptz not null
);

create index idempotency_keys_expires_at_idx on idempotency.idempotency_keys (expires_at);

-- Unlike the ledger schemas this table is mutable by design (attach, reclaim,
-- purge), so the app role gets the full row lifecycle.
grant usage on schema idempotency to nummus_app;
grant select, insert, update, delete on idempotency.idempotency_keys to nummus_app;
