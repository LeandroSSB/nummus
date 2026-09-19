-- M8 operator authentication: the operator identity domain. Same key
-- mechanics as merchants.api_key (nummus_sk secret at issuance, SHA-256
-- hash at rest, display prefix), separate table — one key is one or the
-- other by which table holds its hash.

create table merchants.operator_key (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  key_hash    text not null unique,
  prefix      text not null,
  status      text not null default 'ACTIVE' check (status in ('ACTIVE','REVOKED')),
  created_at  timestamptz not null default now()
);

grant select, insert, update on merchants.operator_key to nummus_app;
