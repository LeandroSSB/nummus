# M7 — Merchant identity and API keys design

**Date:** 2026-09-19 · **Status:** approved in brainstorming
**Builds on:** M1–M6 (the complete initial roadmap)

## Goal

Give every merchant-facing resource an owner. Merchants are created on the
operator surface, authenticate with Bearer API keys (stored hashed, shown
once), and can only see and act on their own payment accounts, payment
intents, webhook endpoints, and idempotency-key namespace — cross-tenant
access is a 404. The simulator, conciliation, and merchant creation remain
operator surfaces.

## Decisions (locked in brainstorming)

1. **Operator-created merchants, self-served keys.** `POST /v1/merchants`
   (open operator surface, like the simulator and conciliation) creates a
   merchant and returns its FIRST API key exactly once. Authenticated by any
   of its keys, a merchant creates, lists, and revokes more. Rejected:
   self-signup with passwords/JWT — session machinery a backend core does
   not need yet (backlog).
2. **Bearer keys, SHA-256 at rest.** `Authorization: Bearer
   nummus_sk_<43 base64url chars>` (256-bit SecureRandom). Stored as
   SHA-256 hex (high-entropy keys need no slow hash); lookup by exact hash
   plus ACTIVE status. Missing, invalid, or revoked → 401 problem+json.
   Rejected: custom header (clients expect Bearer).
3. **Full ownership.** Payment accounts, webhook endpoints, and idempotency
   keys carry `merchant_public_id`; intents are owned through their account
   (ownership checked via the accounts internal API). Cross-tenant reads and
   writes are 404 — a resource you do not own does not exist for you. The
   M4 promise lands here: idempotency key uniqueness becomes
   `(merchant, key)`. Existing rows backfill to a seed merchant created in
   the migration. Rejected: authentication without isolation (facades do
   not fix cross-tenant reads).
4. **Filter + argument resolver + ownership in SQL.** A servlet filter
   (higher precedence than the idempotency filter — no auth means 401
   before any 400) resolves the Bearer to a merchant held as a request
   attribute; a `HandlerMethodArgumentResolver` injects
   `AuthenticatedMerchant` into handler methods — a method declaring the
   parameter is a merchant route, one without it is an operator route.
   Ownership is enforced in the services' SQL (`where merchant_public_id =
   :m`), not at the controller. The auth vocabulary (`AuthenticatedMerchant`,
   the `MerchantAuthentication` port) lives in the shared
   `interfaces.auth` package — the idempotency pattern — so controllers
   never import the merchants module. Rejected: Spring Security (weight
   disproportionate to bearer-only), interceptor-only (no clean 401-before-
   400 ordering).
5. **Operator idempotency namespace.** Idempotency reservations on operator
   POSTs (`POST /v1/conciliation/reports` today) carry a NULL merchant and
   are unique on `key` alone — partial unique indexes keep both worlds
   exact: `unique(merchant_public_id, key) WHERE merchant_public_id IS NOT
   NULL` and `unique(key) WHERE merchant_public_id IS NULL`.
6. **A new `merchants` module.** The locked module list predates merchant
   identity; this milestone adds `merchants` as a peer (entity + keys +
   services + REST). Cross-module references use public ids with no
   cross-schema foreign keys — the payments→accounts precedent.

## Architecture

```
Authorization: Bearer nummus_sk_…            MerchantAuthFilter (HIGHEST+0)
  ├─ no header / bad format / unknown hash / REVOKED  → 401 problem+json
  └─ ACTIVE key → AuthenticatedMerchant as request attribute
IdempotencyWebFilter (HIGHEST+1)             M4, unchanged semantics
  └─ reservation now carries merchant_public_id (NULL for operator routes)
Handler dispatch
  └─ method declares AuthenticatedMerchant → merchant route
       controllers pass merchant.publicId() into services
       services scope every read/write: where merchant_public_id = :m
  └─ no parameter → operator route (merchants, conciliation, simulator)
```

Authentication ordering: a request with neither a Bearer key nor an
Idempotency-Key gets 401 (auth), not 400 (idempotency) — authentication is
the outermost concern.

### Key lifecycle

`POST /v1/merchants {name}` → 201 `{merchantId, name, createdAt, apiKey}` —
the first `nummus_sk_…` secret appears exactly once, here. `POST
/v1/me/api-keys` mints more (secret once, `@Idempotent`); `GET
/v1/me/api-keys` lists `{keyId, prefix, status, createdAt}` (prefix = first
8 chars, secrets never stored or shown again); `DELETE /v1/me/api-keys/{id}`
soft-revokes (status REVOKED; subsequent use → 401 immediately). Key
material beyond the prefix is unrecoverable by design.

### Scoping matrix

| Resource | Scope | Enforcement |
| --- | --- | --- |
| Payment accounts | `merchant_public_id` column (V10) | every accounts SQL statement |
| Payment intents | through owning account | `payments.create/get` take merchant; ownership via accounts internal API → 404 |
| Webhook endpoints | `merchant_public_id` column (V10) | every endpoints SQL statement; deliveries listing 404s other merchants' endpoints |
| Idempotency keys | `(merchant_public_id, key)` partial uniques (V10) | aspect stamps the authenticated merchant (NULL on operator routes) |
| Conciliation reports, simulator, merchant creation | operator | no merchant parameter |
| `GET /v1/me*` | authenticated merchant itself | argument resolver |

## Components

```
merchants/                                   NEW module
  domain/Merchant.java, ApiKey.java
  application/MerchantsService.java          create, resolve-by-hash (authn path)
  application/ApiKeysService.java            create/list/revoke (merchant-scoped)
  application/MerchantAuthentication.java    implements interfaces.auth port
  infrastructure/JdbcClientMerchantsStore.java (merchants + keys)
  interfaces/MerchantsController.java        POST/GET /v1/merchants (operator)
  interfaces/MeController.java               /v1/me, /v1/me/api-keys (self-serve)

interfaces/auth/                             shared layer (idempotency pattern)
  AuthenticatedMerchant.java                 record (merchantPublicId, name)
  MerchantAuthenticationPort.java            Optional<AuthenticatedMerchant> authenticate(String rawKey)
  MerchantAuthFilter.java                    Bearer → attribute; renders 401 itself
  MerchantArgumentResolver.java              injects the attribute; absent → 401

payments/webhooks/accounts (modifications)
  service signatures gain merchantPublicId; SQL gains ownership predicates
  controllers declare AuthenticatedMerchant and pass it through
IdempotencyAspect                              reservation gains merchantPublicId
                                               (request attribute; NULL on operator routes)
```

## REST contract after M7

| Route | Auth | Change |
| --- | --- | --- |
| `POST /v1/merchants` | operator (open) | NEW — 201 + first key once |
| `GET /v1/merchants/{id}` | operator | NEW |
| `GET /v1/me`, `POST /v1/me/api-keys`, `GET /v1/me/api-keys`, `DELETE /v1/me/api-keys/{id}` | Bearer | NEW — self-serve |
| `POST/GET /v1/accounts…`, `POST /v1/payment-intents…` | Bearer | every route now 401s without a key; cross-tenant → 404; `open` binds the account to the authenticated merchant |
| `POST/GET/DELETE /v1/webhook-endpoints…` | Bearer | endpoints owned by the creating merchant; listings scoped |
| `POST /v1/conciliation/reports`, `GET …` | operator | unchanged (idempotency reservation carries NULL merchant) |
| `/simulator/**` | operator | unchanged |
| `Idempotency-Key` semantics | — | uniqueness per (merchant, key); same key across merchants is independent |

Breaking for existing API consumers (intended): every merchant route now
requires a valid Bearer key. The suite's REST tests update accordingly —
each test class authenticates as a merchant created for the test (fixture
via the operator endpoint), and cross-tenant cases assert 404.

## Persistence — `V10__merchants_auth_schema.sql`

```sql
create schema merchants;

create table merchants.merchant (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  name        text not null,
  created_at  timestamptz not null default now()
);

create table merchants.api_key (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  merchant_id bigint not null references merchants.merchant(id),
  key_hash    text not null unique,
  prefix      text not null,
  status      text not null default 'ACTIVE' check (status in ('ACTIVE','REVOKED')),
  created_at  timestamptz not null default now()
);

grant usage on schema merchants to nummus_app;
grant select, insert, update on merchants.merchant, merchants.api_key to nummus_app;

-- Ownership columns; existing rows join the seed merchant.
insert into merchants.merchant (public_id, name)
values ('11111111-1111-4111-8111-111111111111', 'seed merchant');

alter table accounts.payment_account
  add column merchant_public_id uuid not null default '11111111-1111-4111-8111-111111111111';
create index payment_account_merchant_idx on accounts.payment_account (merchant_public_id);

alter table webhooks.webhook_endpoint
  add column merchant_public_id uuid not null default '11111111-1111-4111-8111-111111111111';
create index webhook_endpoint_merchant_idx on webhooks.webhook_endpoint (merchant_public_id);

alter table idempotency.idempotency_keys
  add column merchant_public_id uuid;
-- V7 declared `key text not null unique` — the inline constraint is named
-- idempotency_keys_key_key; uniqueness moves to the partial indexes below.
alter table idempotency.idempotency_keys
  drop constraint idempotency_keys_key_key;
create unique index idempotency_keys_merchant_key_uq
  on idempotency.idempotency_keys (merchant_public_id, key)
  where merchant_public_id is not null;
create unique index idempotency_keys_operator_key_uq
  on idempotency.idempotency_keys (key)
  where merchant_public_id is null;
```

(The aspect only INSERTs `merchant_public_id` — the existing insert grant
covers it; no new grant. The V10 migration is the single composition point,
as with the clearing-account seed in V5.)

## Error vocabulary

- `MerchantUnauthorizedException` (interfaces.auth) → 401 `merchant-
  unauthorized` ("A valid API key is required") — rendered by the filter
  itself (outside the advice's reach), same problem+json shape.
- UnknownApiKey / revoked → same 401 (never 404: key existence is not
  disclosed).
- Cross-tenant → the resource's existing 404 (`UnknownPaymentAccount…`),
  now scoped — no new exception.

## Testing

- **Schema + roles**: V10 shape; seed merchant exists; backfill (accounts/
  endpoints/idempotency rows point at the seed); partial uniques behave
  (same key two merchants OK; duplicate within a merchant 23505; duplicate
  operator-scope 23505); grants under `nummus_app` (api_key needs UPDATE
  for revocation).
- **Auth filter**: no header / malformed Bearer / unknown hash / revoked →
  401 problem+json; valid → request passes with the attribute; operator
  routes never require auth; ordering — no Bearer AND no Idempotency-Key →
  401 (not 400).
- **Merchants REST**: create 201 + first key exactly once (prefix visible,
  secret never again); `POST /v1/me/api-keys` idempotent under M4 and
  secret-once; list shows prefixes only; revoke → immediate 401 on use;
  revoked key's merchant unaffected by other keys.
- **Scoping E2E**: merchants A and B — A opens an account, creates an
  intent, registers a webhook endpoint; B gets 404 on every one of them
  (get/freeze/balance/statement/intent/endpoint/deliveries); B cannot
  unfreeze-or-worse A's account; the same Idempotency-Key on A and B
  executes independently (two resources); conciliation ingest still works
  keyless-merchant (operator namespace).
- **Suite migration**: every existing REST test authenticates (fixture
  merchant per class); integration tests calling services directly pass an
  explicit merchant.
- **Architecture**: `merchants` depends on nothing from other business
  modules; business modules' controllers import only `interfaces.auth`.

## Build constraint (standing since M5)

No Maven/JVM runs on the workstation; verification runs in the containerized
build on megalan (`~/nummus-ci` + `nummus-m2`), per the established recipe.

## Out of scope (recorded to prevent re-litigation)

- Operator authentication (the merchant-creation surface stays open until
  an operator identity model exists), key expiry and rotation policy,
  `last_used_at` tracking (a write per request), rate limiting, IP allow-
  lists, SSRF target-range rejection at webhook registration (registration
  is now authenticated; range checks stay in the backlog), password
  sessions, multi-currency, per-merchant conciliation scoping (reports stay
  operator-global).
