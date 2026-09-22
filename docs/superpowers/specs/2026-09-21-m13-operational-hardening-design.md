# M13 — Operational Hardening

**Date:** 2026-09-21
**Status:** approved design, pending implementation plan

## Goal

Collect the recorded fast-follows from the M11 and M12 reviews into one milestone: a uniform fail-fast configuration posture, the future-dated-ingest stall guard, sub-millisecond `expiresIn` and request-size overflow guards, scheduler failure-log polish, shared test fixtures, and the M12 review's missing test pins. Nothing here adds a product capability — it closes the whole misconfiguration class and the one operational hazard M12 introduced, and clears the runway for the next product milestone.

## Product decisions (locked)

1. **Degenerate configuration fails startup, uniformly.** Every `nummus.*` properties record carries `@Validated` plus constraint annotations; a bad value aborts boot with a clear binding error instead of producing a permanently-429ing or overflow-prone runtime.
2. **The stall guard has two layers:** the manual ingest route rejects a window end beyond `now + max-window-ahead` (400), and the scheduler warns — once per stall episode — when a no-op tick is caused by an existing report's `period_to` sitting ahead of the lagged now.
3. **Sub-millisecond `expiresIn` is rejected** (`toMillis() < 1`): no stillborn keys that mint 201 but can never authenticate.
4. **`max-body-bytes` is bounded above** (`@Max(Integer.MAX_VALUE - 1)`): the `readNBytes(max + 1)` overflow becomes unreachable by configuration.
5. **Scheduler failures log one real warn.** The tick runs its window inside an injected `TransactionTemplate` — the cause is logged once at WARN, the transaction (report + digest + marker) rolls back atomically, and no escaped `UnexpectedRollbackException` reaches the scheduler at ERROR.

## Configuration posture

All five records gain `@Validated` (jakarta validation is already on the classpath — controllers use `@Valid`):

| Record | Constraints |
| --- | --- |
| `WebhookProperties` | `maxAttempts @Min(1)`, `backoffBase @Positive`, `batchSize @Min(1)`, `retentionDays @Min(0)` |
| `RateLimitProperties` | capacities `@Min(1)`, refills `@Min(0)` |
| `HttpProperties` | `maxBodyBytes @Min(1)` and `@Max(2147483646)` |
| `ApiKeyProperties` | `rotationGrace @Positive` |
| `ConciliationProperties` | `pollDelayMs @Min(1)`, `initialDelayMs @Min(1)`, `windowLag @PositiveOrZero`, `maxWindowAhead @Positive` |

`window-lag = 0` stays legal (a documented "I accept the M6 skew risk" choice); everything else degenerate is a startup failure. Tests use `ApplicationContextRunner` loading only the configuration-properties infrastructure — one boot-failure test per record plus the `max-body-bytes` overflow edge; no containers involved.

## Stall guard

**Route guard.** `ConciliationService.ingest` — next to the existing `from.isBefore(to)` check — rejects `to` after `Instant.now().plus(properties.maxWindowAhead())` via `IllegalArgumentException` (→ 400 problem+json through the existing mapping). New property: `nummus.conciliation.max-window-ahead`, default `PT5M`. Manual ingests with a sane future margin (clock jitter between request formation and validation) keep working; a typo'd 2036 window is a 400, never a written report.

**Warn-on-stall.** The worker's no-op path distinguishes its cause: when the self-healing start outruns the lagged end **because `max(period_to)` is ahead of now** (as opposed to a merely caught-up marker), it logs one WARN naming the offending `period_to`, deduplicated by a volatile in-process flag that resets when the condition clears. Pre-existing future-dated rows and clock steps become observable instead of silently disabling automated divergence alerting.

## Expiry and size guards

- `ApiKeysServiceImpl.requirePositiveExpiry` additionally rejects durations with `toMillis() < 1` (message names the minimum: one millisecond). Applies to mint and rotation on both key kinds through the shared validator.
- The `@Max(2147483646)` bound on `max-body-bytes` (above) is the overflow half; no code change needed in the filter.

## Scheduler failure polish

`ConciliationWorker`: `tick()` loses `@Transactional` and instead runs `runWindow()` inside an injected `TransactionTemplate` (from the `PlatformTransactionManager`). On exception: one WARN with the real cause, full rollback of report + digest + marker, retry next tick — the swallow becomes real, with identical atomicity semantics to M12's fix.

## Test fixtures and pins

**Shared fixtures.** A static helper in `testutils` (composition — `IntegrationTestBase` remains the only base class) collects the helpers copied across eight-plus suites: `operatorAuth`, `createMerchantAndGetKey`, `loopbackUrl`, `registerEndpointAndGetId`, `settlePayment`/`settleAndHideExternalCharge`. Each suite switches to the helper; purely mechanical, behavior- and count-neutral.

**M12-review pins:**
- Cross-catalog event-type rejections: an operator registering `payment_intent.settled` → 400; a merchant registering `conciliation.report_open` → 400.
- Reverse-direction namespace isolation: a merchant's list contains no operator endpoints; `GET /v1/webhook-endpoints/{operatorEndpointId}` as a merchant → 404.
- Operator pagination independently pinned: `limit` 0 and 101 → 400; unknown cursor → empty page without `Next-Cursor`.

## Error handling

- Degenerate config → startup failure (binding validation), never a runtime surprise.
- Future-dated `to` → 400 problem+json, existing vocabulary, no report written.
- Stalled scheduler → one WARN per episode naming `period_to`; ticks continue no-opping safely.
- Tick failure → one WARN with the cause; transactional rollback; unchanged retry semantics.

## Testing

1. **Validation:** one `ApplicationContextRunner` boot-failure test per record (e.g. `refill-per-second=-1`, `maxAttempts=0`, `maxBodyBytes=0`, `maxBodyBytes=2147483647`, `rotationGrace=PT0S`, `windowLag=PT-1S`, `maxWindowAhead=PT0S`, `pollDelayMs=0`) and one boot-success test for the legal edge `window-lag=0`.
2. **Route guard:** `to` beyond `now + slack` → 400 and no report row; `to` within slack → 201 (or the window's natural result); the slack property binds.
3. **Warn-on-stall:** a future-dated report row planted via SQL → one WARN (captured via a ListAppender), a second tick logs nothing further; clearing the condition (backdating the row) re-arms the warn.
4. **Sub-ms expiry:** `{"expiresIn":"PT0.0005S"}` on mint → 400; `PT0.001S` mints normally.
5. **TransactionTemplate:** a forced failure inside the window rolls back report, digest event, and marker together (existing rollback semantics re-pinned against the new boundary); the log carries the original cause at WARN, and no `UnexpectedRollbackException` escapes.
6. **Pins:** the four M12-review pins above (cross-catalog ×2, reverse isolation, operator pagination bounds/unknown cursor).
7. **Fixtures:** the refactored suites stay green with unchanged counts — the refactor must be provably behavior-neutral.

Baseline at plan time: 314 tests, all green.

## Non-goals / deliberate bounds

- **Startup-time validation only** — runtime re-binding/refresh of properties stays out of scope.
- **The stall warn is per-process** — a second instance warns independently (same single-process stance as the workers).
- **No pagination-helper extraction** — two mirrored controllers are pinned independently instead; extraction waits for a third copy (recorded in the backlog).
- **No new capability rows** — this milestone hardens; the README gains only the Status line.
