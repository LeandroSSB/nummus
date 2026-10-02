# M29 — Statement export completeness

## Problem

M28 gave the merchant reads a CSV representation, but the statement export
is second-class: its probe rides the JSON statement's `Page`, whose 500-row
ceiling caps the export at 500 journal lines — versus the 10 000-row bound
the listings honor — and a complete-but-exactly-full export gets a
conservative `# truncated: true` it did not strictly earn. The statement is
the accounting surface; its export must carry the same completeness
guarantee as every other export.

## Goals

- An export-sized statement read: a repository-level statement-lines query
  taking a raw limit (no `Page` ceiling), used ONLY by the CSV path; the
  JSON statement handler is untouched.
- The statement CSV honors `nummus.export.max-rows` (default 10 000) exactly
  as the listings do: probe `max + 1`, drop to `max`, emit
  `# truncated: true` ONLY when rows were actually dropped. The two-arm
  probe-full condition and its `Math.min` slice clamp are removed — the
  single `> exportMaxRows` arm is exact again.
- Composition figures (balance/pendingIncoming/reservedOutgoing) still come
  from the service's composed statement — computed once, shared by both the
  header section and (unchanged) the JSON path.
- A test proving a >500-line export: seed 501 statement lines (bulk via
  transfers is too slow — seed via direct journal postings through the
  ledger port in a loop, the funding-recipe primitive, or backdated batch
  inserts through `adminConnection` if the ledger port proves too slow —
  whichever the plan finds honest), request the CSV, assert 500 data rows
  are NO LONGER the ceiling: with `nummus.export.max-rows=600` the export
  carries 501 rows and no marker.

## Non-goals

- Changing the JSON statement's `Page` semantics or its 500-per-page cap
  (paging remains the JSON recovery path).
- Streaming/chunked responses; the 10k bound stands.

## Approaches considered

1. **A dedicated export statement read (chosen).** The ledger repository
   already owns `statementLines(account, offset, limit)`-shaped queries; an
   overload or sibling method with a raw int limit serves the export
   directly. One caller, no contract drift.
2. Strided `Page` probes up to the cap. N queries per export and
   offset-paging semantics — the exact pattern keyset pagination exists to
   avoid. Rejected.
3. Raising `Page`'s ceiling. Weakens the JSON handler's input validation
   for an export concern. Rejected.

## Design

- `Ledger`/`LedgerRepository` (wherever `statement(...)` lives) gains
  `List<StatementLine> statementLinesUpTo(UUID ledgerAccountPublicId,
  int limit)` — same SQL as the paged read minus offset/ceiling; the
  accounts service exposes it behind a read method the controller's CSV
  branch uses for the lines section while keeping the composed statement
  for the figures.
- The CSV branch: figures from `accounts.statement(..., new Page(0, 1))`
  (composition is computed regardless of page size), lines from the new
  read with `exportMaxRows + 1`, exact truncation arm.
- JSON statement behavior byte-identical.

## Testing

- The >500 proof above (bound 600, 501 lines, no marker).
- Exactness: bound 500 (property-driven), 501 lines → 500 rows + marker.
- Figures unchanged (the M21 composition assertions still pass — existing
  suites are the net).
- JSON statement untouched (existing suite).

## Migration

None — `V28` does not exist; schema stays at `V27`.
