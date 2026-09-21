-- M11 API hardening: key lifecycle. expires_at is dual-purpose — the
-- operator-chosen lifetime at mint (null = never) and the rotation grace
-- end (least() of the two). last_used_at is stamped best-effort on every
-- successful authentication; it never gates authentication.

alter table merchants.api_key
  add column expires_at   timestamptz null,
  add column last_used_at timestamptz null;

alter table merchants.operator_key
  add column expires_at   timestamptz null,
  add column last_used_at timestamptz null;
