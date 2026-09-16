# nummus — Claude guide

Payment account core: immutable double-entry ledger, instant (Pix-style) payment processing, idempotent merchant APIs, webhooks via transactional outbox, conciliation. Backend only.

## Repo guardrails

- This is a **public repository** read as a real product. Never describe the project — in code, docs, commits, PRs, or comments — as a portfolio/showcase/study/learning/demo project. No "toy"/"example" framing anywhere.
- No placeholder code ("TODO: real implementation later"). Ship real code or track the gap as a milestone in the README.
- Language: **English everywhere** — code, identifiers, comments, docs, commit messages.
- Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`).

## Locked decisions

Do not relitigate these without explicit instruction:

- **Modular monolith**, hexagonal (ports & adapters). Modules: `accounts`, `ledger`, `payments`, `webhooks`, `conciliation`, `psp-simulator`. Each module owns its schema and talks to others only via internal APIs — no cross-module table access.
- **Java 25 (LTS)**, Spring Boot, PostgreSQL, Maven.
- **Money is `BigDecimal`** with explicit currency; never `double`/`float`. BRL only for now.
- **Ledger is append-only**: entries are never updated or deleted; corrections are compensating entries. Every transaction must balance to zero — enforced in code and in the database.
- **Balances are derived** from the journal; no mutable balance column as source of truth.
- **Idempotency**: merchant-facing writes require an `Idempotency-Key`; responses are stored and replayed on retry.
- **Webhooks** use the transactional outbox pattern with at-least-once delivery and retries with backoff.
- **The external payment network is `psp-simulator`** — it implements the same port a real PSP adapter would. Production code paths never mock the boundary.

## Tooling notes

- Host has JDK 25 but **no global Maven/Gradle** — always use the Maven wrapper (`./mvnw`) once the project is bootstrapped.
- Tests: JUnit 5; Testcontainers for anything Postgres-dependent.

## Workflow

- Follow superpowers: brainstorm → spec in `docs/superpowers/specs/` → writing-plans → implement with TDD.
- New feature? `superpowers:brainstorming` first. Bug? `superpowers:systematic-debugging` first.
- Never implement a feature without tests; never claim done without running them.
