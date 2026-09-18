# nummus

Payment account core: an immutable double-entry ledger, instant payment processing, and idempotent merchant APIs — the foundations a payment provider needs before moving real money.

## Why

Payment providers hold funds on behalf of merchants and must answer, at any moment and provably: *how much does each account hold, and where did every cent come from?* A mutable `balance` column cannot answer that. nummus treats money movement as accounting: every operation is an immutable, balanced journal entry, and balances are derived — never stored as the source of truth.

## Capabilities

| Area | What it provides |
| --- | --- |
| **Accounts** | Payment accounts for merchants, with full transaction history and derived balances |
| **Ledger** | Immutable double-entry journal; invariants enforced at write time (balanced transactions, no entry ever updated or deleted) |
| **Instant payments** | Payment intents and Pix-style dynamic charges: create, expire, settle |
| **Idempotency** | Merchant-facing writes are safe to retry — `Idempotency-Key` handling with stored responses |
| **Webhooks** | At-least-once event delivery to merchants via transactional outbox, with retries and backoff |
| **Conciliation** | Matching of external settlement reports against internal ledger entries, with divergence tracking |
| **PSP simulator** | A first-class simulator plays the external payment network — the same contract a real PSP integration would implement |

## Non-goals

- **Moving real money.** nummus never talks to a real payment network; the PSP simulator covers the entire external boundary.
- **Card acquiring** — out of scope.
- **Multi-currency** — BRL only, by design, initially.
- **Microservices.** Deliberately a modular monolith: strong module boundaries inside one deployable. Distribution is a decision to be earned, not a starting point.

## Architecture

Modular monolith with hexagonal (ports & adapters) boundaries. Each module owns its schema and exposes an internal API; modules never reach into each other's tables.

```
accounts        Payment account lifecycle and holder data
ledger          Double-entry journal, balance derivation, invariants
payments        Payment intents, charge lifecycle, expiration
webhooks        Outbox, delivery worker, merchant endpoints, retries
conciliation    Settlement report ingestion and matching
psp-simulator   Contract-faithful fake of the external payment network
```

## Target stack

Java 25 (LTS) · Spring Boot · PostgreSQL · Maven (wrapper included) · JUnit 5 + Testcontainers

## Status

In active development. Current milestones:

- [x] M1 — Ledger core: journal, invariants, derived balances
- [x] M2 — Accounts and REST API skeleton
- [ ] M3 — Payment intents with the PSP simulator
- [ ] M4 — Idempotency layer for merchant APIs
- [ ] M5 — Webhooks via transactional outbox
- [ ] M6 — Conciliation reports
