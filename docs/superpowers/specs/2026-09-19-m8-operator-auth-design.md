# M8 — Operator authentication design

**Date:** 2026-09-19 · **Status:** approved in brainstorming
**Builds on:** M1–M7

## Goal

Close the open operator surfaces. Merchant creation and conciliation — today
reachable by anyone — require an operator API key: the same key mechanics as
merchants (Bearer, SHA-256 at rest, secret shown once, soft revocation) but a
separate identity domain. The first key bootstraps one-time from a deployment
token; afterwards operators self-serve. The simulator stays open by design —
it is the external network harness and would not exist in a real deployment.

## Decisions (locked in brainstorming)

1. **Operator keys, own table, full lifecycle.** `merchants.operator_key`
   (V11) mirrors `api_key` mechanics — `nummus_sk_…` 256-bit, SHA-256 hex
   stored, 12-char display prefix, ACTIVE/REVOKED, secret visible exactly
   once at mint. An authenticated operator mints, lists, and revokes keys
   (`/v1/operator/api-keys`). Rejected: a static config token (no API
   rotation, secret living in config); a role column on the merchant
   `api_key` (two identity domains in one row).
2. **One-time env bootstrap.** The deployment sets
   `nummus.operator.bootstrap-token`. `POST /v1/operator/bootstrap` with that
   token (constant-time comparison) mints the first operator key. No token
   configured → 404 (endpoint absent); any ACTIVE operator key exists → 410
   (bootstrap consumed). No secret ever lives in a migration or a log.
3. **Merchants + conciliation gated; simulator open.** `POST/GET
   /v1/merchants*` and `/v1/conciliation/*` require an operator key. A valid
   MERCHANT key on an operator route is 403 `operator-key-required`
   (authenticated, wrong role — the honest code), not 401. `/simulator/**`
   stays key-free, documented as a non-production harness.
4. **Two-stage Bearer resolution, sibling resolvers.** The auth filter
   resolves the Bearer hash against `operator_key` first, then merchant
   `api_key`, setting `auth.operator` or `auth.merchant` respectively (a key
   is one or the other by which table holds its hash). An
   `OPERATOR_ROUTES` prefix list (sibling of `MERCHANT_ROUTES`) keeps the
   401-before-400 ordering for headerless operator POSTs — the conciliation
   ingest case. Handlers declare `AuthenticatedOperator`; its resolver
   throws `OperatorUnauthorizedException` (401) without the attribute and
   `OperatorKeyRequiredException` (403) when the caller is a merchant. The
   mirror holds: an OPERATOR key on a merchant route is 403
   `merchant-key-required` (the merchant resolver checks the operator
   attribute before throwing 401). Role mismatches are 403 both ways;
   only missing/invalid credentials are 401.
   The operator-route predicate covers `/v1/merchants`, `/v1/conciliation`,
   and `/v1/operator` — EXCEPT `/v1/operator/bootstrap`, which must stay
   reachable token-first (an explicit exemption in the predicate; without
   it the prefix list would gate the recovery path behind the very keys it
   mints).

## Architecture

```
Authorization: Bearer nummus_sk_…          MerchantAuthFilter (unchanged order)
  ├─ hash in operator_key (ACTIVE)  → auth.operator  (AuthenticatedOperator)
  ├─ hash in api_key      (ACTIVE)  → auth.merchant  (AuthenticatedMerchant)
  └─ neither                         → 401 on operator/merchant routes;
                                        pass-through elsewhere
/v1/merchants*, /v1/conciliation/*          OPERATOR_ROUTES (V-prefix list)
  ├─ no credentials          → 401 (before the idempotency filter's 400)
  ├─ merchant credentials    → 403 operator-key-required
  └─ operator credentials    → AuthenticatedOperator → handler
/v1/operator/bootstrap                      one-time env-gated mint
POST /v1/conciliation/reports (operator-credentialled)
  └─ idempotency reservation keeps the NULL-merchant namespace — an operator
     request carries no merchant attribute, and the aspect already
     namespaces by credential; zero M4-code change.
```

### Key lifecycle

`POST /v1/operator/bootstrap` with body `{"token": "<the configured token>"}`
(constant-time comparison) → 201 `{keyId, prefix, status, createdAt, secret}`
(once). The token travels in the body, not the Authorization header — the
header belongs to key credentials, and the bootstrap token is neither a key
nor a bearer credential. `POST /v1/operator/api-keys` mints more
(`@Idempotent`, secret once); `GET` lists prefixes only; `DELETE
/{id}` soft-revokes → 401 on next use. Revocation of the LAST active key
leaves the system un-enterable until the bootstrap token is re-set — an
explicit, documented operational choice (a lockout, not a vulnerability:
bootstrap remains the recovery path whenever the env token is configured).

## Components

```
merchants/
  application/OperatorKeysService.java     mint/list/revoke/bootstrap
  application/IssuedApiKey.java            reused (key + secret)
  application/OperatorStore methods on MerchantStore
      (insertOperatorKey, findActiveOperatorKeyByHash, listOperatorKeys,
       revokeOperatorKey, hasActiveOperatorKey)
  infrastructure/JdbcClientMerchantStore   +operator_key SQL
  interfaces/OperatorKeysController.java   /v1/operator/api-keys (self-serve)
  interfaces/OperatorBootstrapController.java  POST /v1/operator/bootstrap
  interfaces/dto/CreateKeyResponse, ApiKeyResponse   reused

interfaces/auth/
  AuthenticatedOperator.java               record (keyPublicId)
  OperatorArgumentResolver.java            401 without attribute; 403 when
                                           the caller is a merchant
  OperatorKeyRequiredException.java        → 403 (own advice entry)
  MerchantKeyRequiredException.java        → 403 (the mirror case: operator
                                           key on a merchant route)
  OperatorUnauthorizedException            → 401 (joins the 401 entry)
  OperatorBootstrapProperties.java         nummus.operator.bootstrap-token
  MerchantAuthFilter.java                  two-stage resolution +
                                           OPERATOR_ROUTES prefix list

merchants/interfaces/MerchantsController.java       + AuthenticatedOperator
conciliation/interfaces/ConciliationReportsController.java + AuthenticatedOperator
GlobalExceptionHandler                      + 403 entry
```

## REST contract after M8

| Route | Auth | Change |
| --- | --- | --- |
| `POST /v1/operator/bootstrap` | env token (one-time) | NEW — first key mint |
| `POST/GET/DELETE /v1/operator/api-keys*` | operator key | NEW — self-serve |
| `POST /v1/merchants`, `GET /v1/merchants/{id}` | operator key | 401 without, 403 with merchant key |
| `POST/GET /v1/conciliation/reports*` | operator key | same gating; idempotency reservations keep the NULL namespace |
| `/simulator/**` | none | unchanged (non-production harness) |
| merchant routes (`/v1/me*`, accounts, intents, webhook endpoints) | merchant key | unchanged — except an operator key now yields 403 `merchant-key-required` (role mismatch, both directions 403) |

## Persistence — `V11__operator_keys_schema.sql`

```sql
-- M8 operator authentication: the operator identity domain. Same key
-- mechanics as merchants.api_key, separate table — one key is one or the
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
```

## Testing

- **Schema + roles**: V11 shape; hash global-unique; revocation UPDATE
  under `nummus_app`.
- **Filter/resolver**: 401 (no credentials, unknown key, revoked key) on
  operator routes; 403 with a valid merchant key; ordering — conciliation
  POST without Bearer AND without Idempotency-Key → 401 not 400; operator
  POST with Bearer but without Idempotency-Key → 400 (idempotency still
  applies to operator POSTs); simulator stays key-free.
- **Bootstrap**: one-time (second call 410), 404 when the env token is
  unset, constant-time comparison (not assertable externally — pinned by
  code review + a same-length wrong-token 401 case), the minted key
  authenticates.
- **Self-serve**: mint (secret once, @Idempotent replay verbatim), list
  prefix-only, revoke → 401 on use, double-revoke 404.
- **Gating E2E**: merchant creation as operator works; as merchant → 403;
  keyless → 401; conciliation ingest with an operator key replays in the
  NULL namespace (idempotent POST twice → `Idempotency-Replayed: true`).
- **Fixtures**: suites mint operator keys via `OperatorKeysService`
  directly (service-level, the M7 pattern); only the bootstrap test hits
  the bootstrap endpoint (test context sets the bootstrap property).
- **Architecture**: `merchants` self-containment rule unchanged (operator
  code lives in merchants + shared interfaces.auth); no new module edges.

## Build constraint (standing since M5)

No Maven/JVM runs on the workstation; verification runs in the containerized
build on megalan per the established recipe.

## Out of scope (recorded to prevent re-litigation)

- Operator action audit log, key expiry/rotation policy, `last_used_at`,
  IP allowlists, multi-role RBAC, gating the simulator, per-operator
  identity (keys are role-level, not person-level).
