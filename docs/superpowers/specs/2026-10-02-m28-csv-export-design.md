# M28 — Merchant data export (CSV)

## Problem

Merchants can read their data over JSON — paginated listings and the
statement — but cannot take it anywhere: no accounting system ingest, no
spreadsheet, no archive. Every real payment API offers CSV; nummus answers
JSON only.

## Goals

- `Accept: text/csv` content negotiation on the merchant read endpoints:
  - `GET /v1/payment-intents?status=&account=`
  - `GET /v1/payouts?status=&account=`
  - `GET /v1/refunds?status=&account=`
  - `GET /v1/transfers`
  - `GET /v1/accounts/{id}/statement`
  Same queries, same filters, same auth — the representation changes, not the
  resource.
- CSV shape: header row with the exact JSON field names (camelCase), one row
  per record, newest first (the listing order), comma-separated, UTF-8, CRLF
  line endings, and RFC 4180 quoting (fields containing comma, quote, or
  CR/LF wrapped and doubled). Amounts render as `toPlainString()`.
- Exports are bounded: at most 10 000 rows; a final comment row
  `# truncated: true` is appended when the bound is hit (and `false` never
  appears — the row exists only on truncation). Pagination parameters
  (`after`/`limit`) are IGNORED on CSV requests — an export is the whole
  current result, not a page; `limit` out of range therefore rejects as
  usual only for JSON requests (CSV requests do not carry `limit`).
- The statement CSV gains the composition header as its first data section:
  one header row (statement: `balance,pendingIncoming,reservedOutgoing`)
  followed by a values row, then a blank line, then the lines table —
  accounting-tool friendly.
- `Content-Type: text/csv;charset=UTF-8` and
  `Content-Disposition: attachment; filename="<resource>-<merchantShortId>.csv"`.

## Non-goals

- Async/scheduled exports or email delivery (a 10k-row synchronous bound
  serves the current scale; job-based export is future work).
- Custom column selection, date-range filters (backlog), operator surfaces,
  XLSX.

## Approaches considered

1. **Content negotiation on the existing endpoints (chosen).** One resource,
   two representations; filters compose for free; no new routes to govern.
2. Separate `/export` endpoints. Doubles the route surface and drifts from
   the JSON contract. Rejected.
3. Client-side conversion from JSON. Not an API capability; pagination makes
   it lossy. Rejected.

## Design

- A single CSV rendering helper (payments/interfaces or a shared
  `interfaces/csv` support class): takes the header names and a
  `List<List<String>>` of rows; applies RFC 4180 quoting and CRLF. One
  implementation, five call sites.
- Each listing controller: when the request's `Accept` includes
  `text/csv` (the existing `@GetMapping` gains a `produces`-agnostic shape —
  inspect `RequestHeader("Accept")` in-method, defaulting to JSON for
  `*/*`), fetch up to 10 000 + 1 rows (reuse the repository `limit`
  parameter), render, and set the export headers. JSON behavior is
  byte-identical to today.
- The statement controller follows the same negotiation with its two-section
  shape.
- Determinism: rows render in the queries' existing order (identity desc);
  the JSON field order of each DTO defines the CSV column order.

## Testing

- Per endpoint: CSV request → 200, `text/csv` content type, disposition
  filename, header row equals the DTO field list, row count matches seeded
  fixtures, newest first, quoting on a memo containing a comma and a quote
  (seed one such memo via a transfer), CRLF endings.
- Truncation: seed beyond the bound is impractical — instead drive the bound
  down via a test-only property (`nummus.export.max-rows`, default 10000)
  and assert the `# truncated: true` row at a 3-row bound with 4 seeded.
- Filters apply (`status=` narrows the CSV exactly as the JSON).
- JSON requests unchanged (existing suites are the regression net).
- `after`/`limit` ignored on CSV (limit=999 + CSV → full result, no 400).

## Migration

None — `V27` remains the head. The bound rides a property, not schema.
