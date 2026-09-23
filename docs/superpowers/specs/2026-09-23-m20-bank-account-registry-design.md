# M20 — Bank-Account Registry Design

**Goal:** Payout destinations become registered, verified, merchant-scoped
bank accounts instead of raw opaque keys. Registering captures the
structured Brazilian payout shape; a one-time-code verification gates
usage; payouts reference the registered account and derive the wire key
the network sees.

**Spec status:** approved design — the four forks were validated with the
project owner: structured fields (not named opaque keys), two-step code
verification (not operator approval), registry-only payouts (no dual
path), and placement inside the merchants module (not a new module).

## Scope

1. **The entity.** `merchants.domain.BankAccount(merchantPublicId,
   publicId, bankCode, branch, accountNumber, holderTaxId, status,
   createdAt, verifiedAt)`. Field shapes, validated at registration:
   `bankCode` exactly 3 digits; `branch` 1–5 digits with an optional
   `-d` check digit; `accountNumber` 1–20 chars of digits and dashes;
   `holderTaxId` 11 digits (CPF) or 14 (CNPJ) **with check-digit
   validation** — pure arithmetic, no external calls. Statuses:
   `PENDING_VERIFICATION` → `VERIFIED`; `REVOKED` is the soft-delete
   terminal (the webhook-endpoint precedent).
2. **The wire key is derived, never stored as identity.** At payout
   creation the service derives `{bankCode}-{branch}-{accountNumber}` —
   charset-safe for every existing downstream validation — and that value
   flows exactly where the raw key flowed: the payout row, the network
   transfer, lifecycle events, and responses. `holderTaxId` stays
   registry-only.
3. **Two-step verification.** Registration answers 201 with a one-time
   verification code — random, SHA-256 hashed at rest (the API-key
   precedent), shown once in that response and never again.
   `POST /v1/bank-accounts/{id}/verify {code}` performs a status-guarded
   transition (`where status = 'PENDING_VERIFICATION'`) stamping
   `verified_at`; a wrong code is 401 with no mutation; verify on a
   terminal account is 409. Brute-force resistance rides the existing
   per-tenant rate limiting.
4. **REST surface** (merchant auth, `@Idempotent` writes):
   `POST /v1/bank-accounts`, `GET /v1/bank-accounts` (merchant-scoped,
   50 most recent), `GET /v1/bank-accounts/{id}`,
   `POST /v1/bank-accounts/{id}/verify`, `DELETE /v1/bank-accounts/{id}`
   (revoke; double-revoke 409). Cross-merchant access is
   indistinguishable from unknown — 404.
5. **Registry-only payouts.** `POST /v1/payouts` takes `bankAccountId`
   (breaking: `destinationBankKey` leaves the request). The service
   resolves the merchant-scoped account before the ledger-account lock:
   unknown or foreign → 404; `PENDING_VERIFICATION` → 422
   (`BankAccountNotVerifiedException`, problem+json). The derived key
   then feeds the unchanged flow. `PayoutResponse` gains `bankAccountId`
   (additive). Revoking an account blocks new payouts only — in-flight
   payouts captured their key at creation; no stranding.

## Non-goals (bounds, deliberate)

- **No webhook events** for registration/verification — the event
  catalog is lifecycle-only; registration milestones have no precedent
  and gain none here.
- **No audit entries** — merchant self-serve writes are not operator
  writes (the M15 stance).
- **No cursor pagination** on the listing — the fixed recent-50 window
  matches the pre-M10 conciliation/endpoint listings; pagination is the
  standing backlog thread.
- **No default destination** — payouts always name the account; nothing
  is implicit.
- **Check digits validate shape, not existence** — CPF/CNPJ arithmetic
  and field shapes say well-formed, not real; no external verification
  exists in the simulator era.
- **Same destination, two merchants** — both may register the same
  structured account; both derive the same wire key. Transfers do not
  scope by merchant, so this is correct, and each registration carries
  its own verification cycle.
- **The payout request change is breaking and accepted** — the repository
  is the contract; no external consumers exist.

## Schema (V22 — one migration)

`merchants.bank_account`: identity id, `public_id uuid not null default
gen_random_uuid() unique`, `merchant_public_id uuid not null`, the four
structured fields with shape checks, `status` check
(`PENDING_VERIFICATION`, `VERIFIED`, `REVOKED`), `verification_code_hash
text not null`, `created_at`, `verified_at`. A **partial unique index**
on `(merchant_public_id, bank_code, branch, account_number)` where
`status <> 'REVOKED'` — no accidental double registration; registering
again after a revoke is clean. Grants follow the V18 pattern: schema
usage, select/insert, and a column-scoped update on
`(status, verified_at)` — status-guarded transitions are the only
mutations; the code hash is write-once.

## Module placement

All of it inside `merchants` (domain, application, infrastructure,
interfaces) with the payout consumer importing
`merchants.application.BankAccountsService` — the exact seam
`FeeSchedule` already uses. ArchUnit rules are unchanged:
`merchantsStaySelfContained` and `businessModulesNeverTouchMerchants`
already permit precisely this edge. The locked six-module list is
untouched.

## Testing strategy

TDD per plan task, remote CI only (megalan), house idioms, sweeps
carried with every copied recipe (bank accounts carry no windowed
timestamps — no sweep entries needed). Coverage:

- Check-digit validator: valid/invalid CPF and CNPJ, wrong-length
  rejection, digits-only normalization.
- Wire-key derivation: deterministic composition of the three fields.
- REST: registration shape 400s (bank code, branch, account number, tax
  id each); 201 shows the code once and GET never again; verify happy
  path; wrong code 401 without mutation; verify-after-verified 409;
  revoke then double-revoke 409; cross-merchant 404s.
- Schema: field checks, the partial unique (duplicate active
  registration 23505; post-revoke registration clean), grants.
- Payouts: PENDING account → 422; verified account → end-to-end payout
  with the derived key asserted on the transfer row, the payout row, and
  the event payload; foreign account 404; existing payout suites
  re-driven through a `registerVerifiedBankAccount` fixture helper.
- The idempotency layer rides the registry writes like every merchant
  write (`@Idempotent` behavior pinned by the layer's own suites).

## Migration / rollout notes

- V22 is a new table — no renames, no backfill, no changes to existing
  rows. The payout request swap is code-only.
- Pre-M20 payout rows keep their raw keys and read unchanged under the
  response shape (they gain a null `bankAccountId`).
