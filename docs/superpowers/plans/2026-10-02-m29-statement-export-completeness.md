# M29 Statement Export Completeness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The statement CSV honors `nummus.export.max-rows` exactly like the listings — an export-sized raw-limit statement-lines read replaces the `Page`-clamped probe; the two-arm truncation condition and slice clamp are removed; JSON untouched.

**Architecture:** `LedgerRepository`/`Ledger` gain a raw-limit lines read (same SQL minus offset/ceiling); `AccountsService` exposes it; the controller's CSV branch takes figures from the composed statement (`Page(0, 1)`) and lines from the new read.

**Tech Stack:** Java 25, Spring Boot, JUnit 5, Testcontainers.

## Global Constraints

- English everywhere. Conventional Commits.
- JSON statement byte-identical (existing suites are the net).
- Truncation: probe `exportMaxRows + 1`, emit `# truncated: true` ONLY when rows were dropped; slice to `exportMaxRows`.
- **Verification protocol (no local Maven):** implementers edit, run no build, commit (test first, then implementation). The controller verifies remotely on megalan after each task; include `ModuleBoundaryTest`.

---

### Task 1: Export-sized statement read and the exact CSV branch

**Files:**
- Modify: the ledger port and JDBC repository owning `statement`/`statementLines` (locate by grep `statementLines`)
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsService.java` + impl
- Modify: `src/main/java/com/leandrossb/nummus/accounts/interfaces/AccountsController.java` (CSV branch only)
- Modify: in-memory ledger repository fakes (grep `implements LedgerRepository` in `src/test`)
- Test: `src/test/java/com/leandrossb/nummus/accounts/StatementExportCompletenessTest.java`

**Interfaces:**
- Produces: `List<StatementLine> statementLinesUpTo(UUID ledgerAccountPublicId, int limit)` on the ledger port (name may adapt to the port's existing style — keep the semantics); `AccountsService` exposes the same-shaped read scoped through the merchant/account ownership check exactly as `statement` does.

- [ ] **Step 1: Write the failing test**

In the sibling idiom (`StatementCsvTruncationTest` is the closest — read it), one class with `@TestPropertySource(properties = "nummus.export.max-rows=600")`:
1. Seed 501 statement lines. Bulk-seed via `adminConnection` batch INSERTs directly into `ledger.journal_transaction` + `ledger.journal_posting` (read the tables' columns from V1 and the funding recipe's posting shape; each line = one balanced two-posting transaction crediting the account; use one prepared statement in a loop inside a single connection/transaction; backdate `booked_at` a few minutes so ordering is stable). Assert the JSON statement still pages at its own ceiling (optional — skip if slow).
2. Request the statement CSV: exactly 501 data rows, NO `# truncated: true` line, composition figures correct (the seeded credits sum).
3. A second account with 3 lines and the same request: 3 rows, no marker (sub-bound normalcy).

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/accounts/StatementExportCompletenessTest.java
git commit -m "test: cover the export-sized statement read"
```

- [ ] **Step 3: Implement**

- Ledger port + JDBC repository: `statementLinesUpTo` — copy the existing `statementLines` SQL minus the offset and with the raw `limit :limit`; map with the same row mapper.
- Fakes: mirror over their line storage.
- `AccountsService` + impl: `List<StatementLine> statementLinesForExport(UUID merchantPublicId, UUID accountPublicId, int limit)` — the same `require` ownership check as `statement`, then the ledger read.
- `AccountsController` CSV branch: figures via the existing `accounts.statement(..., new Page(0, 1))`; lines via the new read with `exportMaxRows + 1`; `truncated = lines.size() > exportMaxRows`; slice to `exportMaxRows`; DELETE the two-arm condition, the `probeLimit` hoist, the `Math.min` clamp, and their comments (add one comment: the export read bypasses `Page`'s ceiling so the statement honors the same bound as the listings).

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java
git commit -m "feat: honor the export bound on the statement csv"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest='StatementExportCompletenessTest,StatementCsvTruncationTest,StatementCsvExportTest,AccountsRestApiTest,ModuleBoundaryTest'` on megalan. Expected: PASS (the truncation suite's 3-row/4-line case now rides the exact arm — if its expectations encoded the old probe-full semantics, they still hold: 4 > 3 fires the single arm).

---

### Task 2: README — follow-up closed

**Files:**
- Modify: `README.md`

- [ ] **Step 1:** In the Status list append after M28:

```markdown
- [x] M29 — Statement export completeness
```

(No capabilities-table change — the M28 row already says "CSV export on the merchant listings and statement".)

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mark M29 statement export completeness complete"
```

- [ ] **Step 3: Controller verifies**

Full suite on megalan (`./mvnw -B verify`). Expected: BUILD SUCCESS, Failures: 0, Errors: 0.
