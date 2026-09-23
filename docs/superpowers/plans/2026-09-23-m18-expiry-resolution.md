# M18 — Expiry Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expiry resolves the in-flight network instruction instead of abandoning it: a past-expiry read polls the network — SUCCEEDED settles, PENDING is cancelled then returns, FAILED/CANCELLED return. `CANCELLED` becomes a terminal network state; late unaccounted pays become impossible.

**Architecture:** Both lazy lifecycles (payouts, refunds) gain one resolution branch on the expiry path; the simulator gains the CANCELLED status, cancel transitions on both instruction stores, cancel routes, and the port gains the two cancel methods returning post-attempt state. The cancel-vs-pay race closes by construction (single status-guarded row); the payments side keeps the post-legs → guarded-mark → throw race shape.

**Tech Stack:** Java 25, Spring Boot (`@Transactional`, `JdbcClient`), PostgreSQL + Flyway (V20 — constraint swap only), JUnit 5 + Testcontainers, MockMvc, `ApiDrivers` fixtures.

**Spec:** `docs/superpowers/specs/2026-09-23-m18-expiry-resolution-design.md`

## Global Constraints

- **English everywhere**; Conventional Commits; every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Public repository read as a real product** — no portfolio/demo framing anywhere.
- **No local Maven/JVM runs, ever.** All builds/tests run on the megalan CI container. Focused run (branch `worktree-m18-expiry`, replace `<Tests>`):
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m18-expiry origin/worktree-m18-expiry && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<Tests>'
  ```
  Full verify = same with `./mvnw -B verify`. Every task: **push first**, then run remotely. Retry a timed-out ssh once. Remote verify ≈ 2 min.
- **TDD strictly:** failing tests (remote RED), implement, focused GREEN, full verify, commit. Test-first commits use a `test:` prefix; the implementation lands as the task's `feat:` commit. Both carry the trailer.
- **No new dependencies.** Baseline: **424 tests, all green** (verified on merged main). Running totals below are provisional — the authoritative total is 424 + your cumulative `@Test` method count; report the true number.
- **Worktree:** execution starts from a worktree on branch `worktree-m18-expiry`. Never commit on `main`.
- Standing idioms: `ApiDrivers`, loopback URLs, `"Bearer " + secret`, jsonPath reads, shared-container delta counting, `@AfterAll` sweeps carried with every copied recipe, post-legs-then-guarded-mark race shape (throw on loss), invariant pins without barriers.
- The race shape and event-after-won-guard discipline are unchanged everywhere. **An observed CANCELLED behaves like FAILED** in every poll (return legs + terminal mark; `EXPIRED` vocabulary past expiry, `FAILED` vocabulary before).
- Money is `Money.of(BigDecimal, BRL)`; comparisons via `compareTo`/`isPositive`; never `double`.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V20__expiry_resolution.sql (new, T1)
src/main/java/com/leandrossb/nummus/payments/application/ChargeStatus.java (modify, T1 — +CANCELLED)
src/main/java/com/leandrossb/nummus/payments/application/PaymentNetwork.java (modify, T1 — +2 cancel methods)
src/main/java/com/leandrossb/nummus/payments/application/NetworkTransfer.java (unchanged — javadoc note only if needed)
src/main/java/com/leandrossb/nummus/psp_simulator/application/TransferStore.java (modify, T1 — cancel via transition)
src/main/java/com/leandrossb/nummus/psp_simulator/application/RefundStore.java (modify, T1)
src/main/java/com/leandrossb/nummus/psp_simulator/application/SimulatorService.java (modify, T1 — +2 cancels)
src/main/java/com/leandrossb/nummus/psp_simulator/application/SimulatorServiceImpl.java (modify, T1)
src/main/java/com/leandrossb/nummus/psp_simulator/infrastructure/JdbcClientTransferStore.java (modify, T1 — CANCELLED in maps/checks)
src/main/java/com/leandrossb/nummus/psp_simulator/infrastructure/JdbcClientRefundStore.java (modify, T1)
src/main/java/com/leandrossb/nummus/psp_simulator/interfaces/SimulatorController.java (modify, T1 — +2 cancel routes)
src/test/java/com/leandrossb/nummus/payments/application/FakePaymentNetwork.java (modify, T1 — cancels; T2/T3 — cancel-lost driver)
src/main/java/com/leandrossb/nummus/payments/application/PayoutsServiceImpl.java (modify, T2 — resolution branch)
src/test/java/com/leandrossb/nummus/payments/PayoutLifecycleTest.java (modify, T2)
src/test/java/com/leandrossb/nummus/psp_simulator/TransferNetworkTest.java (modify, T1)
src/test/java/com/leandrossb/nummus/psp_simulator/RefundNetworkTest.java (modify, T1)
src/main/java/com/leandrossb/nummus/payments/application/RefundsServiceImpl.java (modify, T3 — resolution branch)
src/test/java/com/leandrossb/nummus/payments/RefundLifecycleTest.java (modify, T3)
src/test/java/com/leandrossb/nummus/payments/RefundsRestApiTest.java (modify, T3 — strand inversion pin)
README.md, docs/m2-backlog.md (modify, T4)
```

(Exact names may differ — grep before editing; the interfaces below are the contract.)

---

### Task 1: `V20` + the network cancel surface

**Files:**
- Create: `V20__expiry_resolution.sql`
- Modify: `ChargeStatus.java`, `PaymentNetwork.java`, `TransferStore.java`, `RefundStore.java`, `SimulatorService.java`, `SimulatorServiceImpl.java`, `JdbcClientTransferStore.java`, `JdbcClientRefundStore.java`, `SimulatorController.java`, `FakePaymentNetwork.java`, `TransferNetworkTest.java`, `RefundNetworkTest.java`
- Test: extensions to the two network suites (new methods count toward the totals)

**Interfaces:**
- Produces: `ChargeStatus.CANCELLED`; `PaymentNetwork.cancelPayoutTransfer(UUID transferPublicId)` → `NetworkTransfer` and `cancelChargeRefund(UUID refundPublicId)` → `NetworkRefund` — each returning the instruction's POST-ATTEMPT state (CANCELLED when the cancel won; the winner's terminal state when it lost; the observed state when already terminal); `SimulatorService.cancelTransfer(UUID)` / `cancelRefund(UUID)`; simulator routes `POST /simulator/transfers/{id}/cancel`, `POST /simulator/refunds/{id}/cancel` (409 on non-PENDING with the not-pending vocabulary, 404 unknown). The network caps (`totalRefunded`, transfer over-issue guard if any) keep summing `PENDING+SUCCEEDED` — CANCELLED releases.

- [ ] **Step 1: Write the failing tests** — extend `TransferNetworkTest` and `RefundNetworkTest` (5 new methods across the two, house judgment on split):
  1. `cancelTransitionsPendingToCancelled` (transfers) — create, POST cancel → CANCELLED; pay after cancel → 409; GET shows CANCELLED; the port `cancelPayoutTransfer` returns the CANCELLED state.
  2. `cancelOnTerminalIs409` (transfers) — after pay → cancel 409; after fail → cancel 409.
  3. `cancelledTransfersReleaseNothingButHoldNothing` — the caps angle for transfers: cancelling does not create over-issue; a new transfer of the same amount still succeeds (transfers have no sum cap — this pin reduces to "cancel is just terminal"; keep it minimal or fold into 1).
  4. `cancelRefundReleasesTheNetworkRemainder` (refunds) — charge 100, refund 60, cancel it → a new refund of 60 SUCCEEDS (cancelled rows don't count — the M17 mirror).
  5. `portCancelReturnsPostAttemptState` — `PaymentNetwork.cancelChargeRefund` on a PENDING refund → CANCELLED echoed.

- [ ] **Step 2: Remote RED** — push; FAIL (compile: CANCELLED absent).

- [ ] **Step 3: Implement** — the migration verbatim:

```sql
-- M18 expiry resolution: CANCELLED joins the instruction statuses so an
-- expiring hold can resolve its network instruction instead of abandoning
-- it. Constraints are swapped (checks cannot be altered in place); grants
-- already cover the columns. Charges never cancel — money-in has no expiry
-- abandonment.

alter table psp_simulator.payout_transfer
  drop constraint if exists payout_transfer_status_check;
alter table psp_simulator.payout_transfer
  add constraint payout_transfer_status_check
  check (status in ('PENDING','SUCCEEDED','FAILED','CANCELLED'));

alter table psp_simulator.charge_refund
  drop constraint if exists charge_refund_status_check;
alter table psp_simulator.charge_refund
  add constraint charge_refund_status_check
  check (status in ('PENDING','SUCCEEDED','FAILED','CANCELLED'));
```

(The inline checks from V18/V19 carry Postgres's conventional `<table>_<column>_check` names — `drop ... if exists` guards the guess, and a wrong name fails loudly at the migration step of the RED run; if the names differ, read them from `pg_constraint` and fix before GREEN.) Then: `ChargeStatus` gains `CANCELLED` (javadoc: terminal, reached only by resolver/operator cancel; charges never enter it); `SimulatorServiceImpl.cancelTransfer/cancelRefund` reuse the exact `transition*` shape with `CANCELLED` (the returned re-read is the post-attempt state by construction — a lost race re-reads the winner's terminal state); the stores' existing `transition(UUID, ChargeStatus)` ports already cover it (grep — likely no store change beyond mapping checks); the two controller routes mirror pay/fail; `SimulatorPaymentNetwork` + `FakePaymentNetwork` delegate/store (the fake's cancel flips a stored instruction to CANCELLED — no race emulation; the cancel-lost driver comes in T2/T3).

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='TransferNetworkTest,RefundNetworkTest'` → green; full verify → BUILD SUCCESS, **429 tests** (424 + 5, provisional).

- [ ] **Step 5: Commit** — `feat: add cancellable network instructions (V20)` + trailer; push.

---

### Task 2: Payout expiry resolution

**Files:**
- Modify: `PayoutsServiceImpl.java`, `PayoutLifecycleTest.java`, `FakePaymentNetwork.java` (cancel-lost driver)
- Test: extensions to `PayoutLifecycleTest`

**Interfaces:**
- Consumes: T1's `cancelPayoutTransfer` + CANCELLED.
- Produces: `PayoutsServiceImpl.get`'s resolution branch — the expired-read semantics below. No signature changes.

- [ ] **Step 1: Write the failing tests** (4 new methods in `PayoutLifecycleTest`, mirroring its fixtures/sweeps):
  1. `expiredPayoutWithPendingTransferCancelsAndReturns` — fund, payout 30, SQL-backdate `expires_at`, GET → EXPIRED: return legs posted, `payout.expired` published, AND the network transfer is CANCELLED (SQL read), AND a fresh payout of the same amount succeeds end-to-end (the reservation lifecycle is fully reusable — the M16 transfer-abandonment mirror of the M17 strand inversion).
  2. `expiredPayoutWithExecutedTransferSettles` — fund, payout 30, `simulator.payTransfer` (SUCCEEDED), THEN SQL-backdate `expires_at`, GET → SETTLED: execution legs posted (`reserved → clearing`), `payout.settled` published with the resolution-instant `settledAt`, execute link set — deterministic because the backdate happens after the pay.
  3. `expiredPayoutWithFailedTransferStillFails` — the existing FAILED path unchanged under expiry (backdate after `failTransfer`) → FAILED + return legs + `payout.failed`.
  4. `cancelLostToAParallelPaySettles` — the cancel-returns-SUCCEEDED branch: drive it through `FakePaymentNetwork` in a focused service-level test (the fake is a test-only boundary double; production paths untouched — the house's `PaymentsServiceImplTest` precedent): a fake whose `cancelPayoutTransfer` returns SUCCEEDED while `getPayoutTransfer` stays PENDING-before-cancel → `get` settles. Alternatively drive it on the real simulator by paying between... the interleave is not drivable deterministically — the fake is the honest pin; state the choice in the report.

- [ ] **Step 2: Remote RED** — push; FAIL (tests 1-2: no cancel, expiry returns without consulting; test 4: no such branch).

- [ ] **Step 3: Implement** — restructure `get`'s expiry path to poll-then-decide (the unexpired path and the amount-echo check stay exactly where they are):

```java
    var transfer = network.getPayoutTransfer(payout.transferPublicId());
    if (transfer.amount().compareTo(payout.amount()) != 0) {
      throw new TransferAmountMismatchException(transfer.publicId(), payout.amount(), transfer.amount());
    }
    if (payout.status() == PayoutStatus.REQUESTED && Instant.now().isAfter(payout.expiresAt())) {
      // Poll-then-decide: an executed instruction settles even past expiry —
      // the money moved; a pending one is cancelled, then the hold returns.
      // A cancel that loses to a parallel pay re-reads SUCCEEDED and settles.
      return switch (transfer.status()) {
        case SUCCEEDED -> execute(payout, merchantPublicId);
        case PENDING -> {
          var after = network.cancelPayoutTransfer(payout.transferPublicId());
          yield after.status() == ChargeStatus.SUCCEEDED
              ? execute(payout, merchantPublicId)
              : toExpired(payout, merchantPublicId);
        }
        default -> toExpired(payout, merchantPublicId);
      };
    }
    return switch (transfer.status()) {
      case PENDING -> payout;
      case CANCELLED, FAILED -> { /* today's FAILED body, CANCELLED riding along */ }
      case SUCCEEDED -> execute(payout, merchantPublicId);
    };
```

  with `toExpired(payout, merchantPublicId)` being today's expiry body extracted verbatim (return legs → `markExpired` → throw on loss → publish `payout.expired`), and the unexpired switch's FAILED arm gaining `CANCELLED` (observed-CANCELLED-before-expiry behaves like FAILED per the spec — extract the FAILED body to a shared `toFailed` if the duplication would otherwise be verbatim). Adjust the method's top so the poll precedes the expiry decision while the terminal early-return and ownership check stay first. Keep the whole method `@Transactional`.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='PayoutLifecycleTest,PayoutRequestTest,PaymentsServiceTest'` (grep the intent-suite name; include the suites covering `PaymentsServiceImpl.get` if distinct) → green; full verify → **433** (429 + 4, provisional).

- [ ] **Step 5: Commit** — `feat: resolve expired payouts through the network` + trailer; push.

---

### Task 3: Refund expiry resolution + the strand inversion

**Files:**
- Modify: `RefundsServiceImpl.java`, `RefundLifecycleTest.java`, `RefundsRestApiTest.java`, `FakePaymentNetwork.java` (if the refund cancel-lost pin lands here instead)
- Test: extensions to both suites

**Interfaces:**
- Consumes: T1's `cancelChargeRefund` + CANCELLED; T2's branch structure as the template.
- Produces: `RefundsServiceImpl.get`'s resolution branch — identical semantics with refund legs/events.

- [ ] **Step 1: Write the failing tests** (5 new methods):
  1. `expiredRefundWithPendingNetworkRefundCancelsAndReturns` — mirror of T2's pin 1 with refund legs/events; network refund CANCELLED via SQL read.
  2. `expiredRefundWithExecutedNetworkRefundSettles` — pay the network refund, THEN backdate, read → SETTLED (`refund.settled`, execution legs `refund-reserved → clearing`).
  3. `expiredRefundWithFailedNetworkRefundStillFails` — unchanged path pin.
  4. `fullRefundExpiredThenCancelledReleasesBothSides` — **the M17 strand inversion, end to end over HTTP**: settle 100, refund 100, backdate + GET (EXPIRED, cancelled on the network), then POST a fresh refund of 100 → **201** (the old 422 pin flips to success; update the M17-era test `expiredRefundStillHoldsTheNetworkRemainder` — it pinned the documented bound that this milestone resolves; rewrite it to assert the new resolution semantics and reference the M18 backlog section).
  5. `cancelLostToAParallelPaySettles` — the refund flavor of T2's pin 4 (fake-driven, same judgment).

- [ ] **Step 2: Remote RED** — push; FAIL.

- [ ] **Step 3: Implement** — apply T2's structure to `RefundsServiceImpl.get` (poll → amount echo → expiry resolution → unexpired switch with CANCELLED riding FAILED), extracting `toExpired`/`toFailed` bodies verbatim from today's branches.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='RefundLifecycleTest,RefundsRestApiTest,RefundRequestTest,RefundNetworkTest'` → green; full verify → **438** (433 + 5, provisional).

- [ ] **Step 5: Commit** — `feat: resolve expired refunds through the network` + trailer; push.

---

### Task 4: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — the **Instant payments** capability row extends with `; expiring holds resolve their network instruction — executed instructions settle, pending ones cancel` (semicolon-clause style); Status gains `- [x] M18 — Expiry resolution`.
- Modify: `docs/m2-backlog.md` — append at the end of the file:

```markdown
## From the M18 design

M18 closed the M17 expiry bound: a past-expiry read resolves the in-flight
network instruction instead of abandoning it — an executed instruction
settles (the money moved), a pending one is cancelled on the network and
the hold returns, a failed one returns as before. `CANCELLED` is terminal,
so late unaccounted pays are impossible and cancelled instructions release
the network-side remainders. Known bounds, deliberate:

- **No retroactive repair** — pre-M18 EXPIRED rows and their orphaned
  PENDING instructions stay as-is; operators may cancel orphans through
  the simulator routes manually.
- **No early abort surface** — cancelling before expiry stays internal to
  the resolution path; a merchant-facing void is future product work.
- **An observed CANCELLED behaves like FAILED** — including the rare
  pre-expiry observation after a resolver rollback or an operator cancel.
- **Charges never cancel** — money-in has no expiry abandonment.
```

- [ ] **Step 1: Remote full verify** — `Tests run: <N>, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (N = 424 + cumulative; per-task totals are provisional).
- [ ] **Step 2+3:** README/backlog edits per the text above.
- [ ] **Step 4: Commit** — `docs: mark M18 expiry resolution complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V20 constraint swap + CANCELLED + cancel surface (port, routes, caps release) | 1 |
| Payout resolution branch (settle-on-executed, cancel-then-return, cancel-lost) | 2 |
| Refund resolution branch + M17 strand inversion | 3 |
| README + backlog (bound superseded) + final gate | 4 |
