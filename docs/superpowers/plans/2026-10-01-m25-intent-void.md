# M25 Merchant Intent Void Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /v1/payment-intents/{id}/void` — a merchant withdraws a CREATED intent; the network charge is cancelled with post-attempt branching (the refund-resolver race pattern), the intent lands in a distinct terminal `VOIDED` state, and `payment_intent.voided` publishes.

**Architecture:** One migration swaps two check constraints (`payment_intent.status` gains `VOIDED`; `psp_simulator.charge.status` gains `CANCELLED`). The port grows `cancelCharge` with catch-and-reread post-attempt semantics. The service resolves the pay-vs-void race by branching on the post-attempt charge state — money already paid settles, void only wins on a truly cancelled charge. No ledger postings anywhere.

**Tech Stack:** Java 25, Spring Boot, PostgreSQL/Flyway, JUnit 5, Testcontainers.

## Global Constraints

- English everywhere (code, comments, commits). Conventional Commits (`test:`, `feat:`, `docs:`, `fix:`).
- Void is legal only on `CREATED`; every other state rejects 409 with the current state carried (lazy expiry on the ownership read settles the expiry question first — the existing ordering stands).
- Post-attempt branching only: decisions branch on the charge state observed AFTER the cancel attempt, never on a pre-read.
- No ledger postings on the void path (a pending charge has no journal existence).
- Won-transition discipline: publish + count only inside the guarded-transition win (the repo's `guardedTransition` + existing publish/count precedent).
- **Test-tree ripple rule:** any interface this plan extends (`PaymentNetwork`, `SimulatorService`, `PaymentsRepository`) has test doubles/fakes — extend them in the same task mirroring semantics.
- **Verification protocol (no local Maven):** implementer subagents edit, run no build, and commit (test commit first, then implementation). The controller runs all Maven commands remotely on megalan after each task:
  `ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B <branch> origin/<branch> && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<TestClass>'`
  Branches are pushed only after a task's green commit. Include `ModuleBoundaryTest` in the verify when touching cross-module types.

---

### Task 1: Charge cancellation on the network (V25, simulator, port)

**Files:**
- Create: `src/main/resources/db/migration/V25__intent_void.sql`
- Create: `src/main/java/com/leandrossb/nummus/psp_simulator/application/ChargeNotPendingException.java`
- Modify: `src/main/java/com/leandrossb/nummus/psp_simulator/application/SimulatorService.java`
- Modify: `src/main/java/com/leandrossb/nummus/psp_simulator/application/SimulatorServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/psp_simulator/interfaces/SimulatorController.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentNetwork.java`
- Modify: `src/main/java/com/leandrossb/nummus/psp_simulator/infrastructure/SimulatorPaymentNetwork.java`
- Modify: test doubles implementing `PaymentNetwork` (grep `implements PaymentNetwork` in `src/test/java`)
- Test: `src/test/java/com/leandrossb/nummus/psp_simulator/ChargeCancelRestApiTest.java`

**Interfaces:**
- Consumes: the existing `transitionTransfer`/`transitionRefund` guarded-transition shapes and `TransferNotPendingException`/`RefundNotPendingException` styles.
- Produces (exact signatures later tasks rely on): `SimulatorService.cancelCharge(UUID publicId)` returning `NetworkCharge`; `PaymentNetwork.cancelCharge(UUID chargePublicId)` returning `NetworkCharge` with post-attempt semantics; `ChargeNotPendingException(UUID publicId, ChargeStatus status)`.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.psp_simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** A pending charge can be withdrawn on the network; a terminal one cannot. */
@AutoConfigureMockMvc
class ChargeCancelRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private SimulatorService simulator;

  @Test
  void cancelWithdrawsAPendingCharge() throws Exception {
    var charge = simulator.create(com.leandrossb.nummus.ledger.domain.Money.ofBrl("10.0000"));
    mockMvc.perform(post("/simulator/charges/{id}/cancel", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    mockMvc.perform(get("/simulator/charges/{id}", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void cancelRejectsATerminalCharge() throws Exception {
    var charge = simulator.create(com.leandrossb.nummus.ledger.domain.Money.ofBrl("10.0000"));
    simulator.pay(charge.publicId());
    mockMvc.perform(post("/simulator/charges/{id}/cancel", charge.publicId()))
        .andExpect(status().isUnprocessableEntity());
  }
}
```

(If the simulator's 4xx mapping for `ChargeNotPendingException` differs from 422 in the global handler — mirror however `TransferNotPendingException` surfaces and adjust the expected status once by reading that mapping.)

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/psp_simulator/ChargeCancelRestApiTest.java
git commit -m "test: cover simulator charge cancellation"
```

- [ ] **Step 3: Implement**

`V25__intent_void.sql`:

```sql
-- M25 intent void: a merchant may withdraw a CREATED intent before payment.
-- Two check-constraint swaps (the V20 drop/add pattern): the intent state
-- machine gains the distinct terminal VOIDED, and the simulator's charge may
-- now be cancelled (previously terminal-only PENDING/SUCCEEDED/FAILED —
-- money-in had no abandonment). No new tables; the app role's existing
-- column-scoped updates cover the new values.

alter table payments.payment_intent drop constraint payment_intent_status_check;
alter table payments.payment_intent
  add constraint payment_intent_status_check
  check (status in ('CREATED','SETTLED','FAILED','EXPIRED','VOIDED'));

alter table psp_simulator.charge drop constraint charge_status_check;
alter table psp_simulator.charge
  add constraint charge_status_check
  check (status in ('PENDING','SUCCEEDED','FAILED','CANCELLED'));
```

(Verify the real constraint names in `V5__payments_schema.sql` and `V6__psp_simulator_schema.sql` — unnamed inline checks get `<table>_<column>_check` by default; adjust the drop clauses to the actual names, including any V20 renames.)

`ChargeNotPendingException.java` (mirroring `RefundNotPendingException`'s package/shape — read it first):

```java
package com.leandrossb.nummus.psp_simulator.application;
```
…with a constructor `(UUID publicId, ChargeStatus status)` and message mirroring the refund variant.

`SimulatorService` — add `NetworkCharge cancelCharge(UUID publicId);`.

`SimulatorServiceImpl` — add beside the existing charge transitions (mirroring `cancelRefund`):

```java
  @Override
  @Transactional
  public NetworkCharge cancelCharge(UUID publicId) {
    return transitionCharge(publicId, ChargeStatus.CANCELLED);
  }
```

(or mirror however the file's pay/fail charge transitions are factored — if `transitionCharge` does not exist, follow the pay/fail method shape with the `CANCELLED` target and the `ChargeNotPendingException` on a lost guard).

`SimulatorController` — add beside `pay`/`fail`:

```java
  @PostMapping("/charges/{id}/cancel")
  NetworkCharge cancel(@PathVariable UUID id) {
    return simulator.cancelCharge(id);
  }
```

(mapping the response type to whatever `pay` returns; add the `ChargeNotPendingException` → 422 handler mapping if the simulator relies on the global handler — mirror how `TransferNotPendingException` surfaces.)

`PaymentNetwork` — add:

```java
  /** Cancels a pending charge; returns the post-attempt charge when the
   *  cancel loses the status guard (the cancelChargeRefund contract). */
  NetworkCharge cancelCharge(UUID chargePublicId);
```

`SimulatorPaymentNetwork` — add (catch-and-reread, mirroring `cancelChargeRefund`):

```java
  @Override
  public NetworkCharge cancelCharge(UUID chargePublicId) {
    try {
      return simulator.cancelCharge(chargePublicId);
    } catch (ChargeNotPendingException e) {
      // Post-attempt semantics as cancelChargeRefund — the observed state
      // when the cancel did not win.
      return simulator.getCharge(chargePublicId);
    }
  }
```

Ripple rule: every test double implementing `PaymentNetwork` gains `cancelCharge` (default: throw `UnsupportedOperationException` or delegate to the fake's charge map — mirror the double's own style for the other cancels).

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java src/main/resources
git commit -m "feat: cancel pending charges on the payment network"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest='ChargeCancelRestApiTest,ModuleBoundaryTest'` on megalan. Expected: PASS.

---

### Task 2: The VOIDED state machine and the void service method

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/payments/domain/IntentStatus.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/domain/PaymentIntent.java` (only if it enumerates states in javadoc)
- Create: `src/main/java/com/leandrossb/nummus/payments/domain/IntentNotVoidableException.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPaymentsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/IntentEventTypes.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java`
- Modify: in-memory `PaymentsRepository` fakes (`InMemoryPaymentsRepository`, the `PaymentsPublishRaceTest` repository)
- Test: `src/test/java/com/leandrossb/nummus/payments/IntentVoidServiceTest.java`

**Interfaces:**
- Consumes: Task 1's `PaymentNetwork.cancelCharge`; the existing `guardedTransition`, publish, and `count` helpers.
- Produces: `PaymentsService.voidIntent(UUID merchantPublicId, UUID publicId)` returning the terminal `PaymentIntent`; `IntentStatus.VOIDED`; `IntentEventTypes.VOIDED` = `"payment_intent.voided"`; `IntentNotVoidableException(UUID publicId, IntentStatus current)` → 409.

- [ ] **Step 1: Write the failing service test** — integration style over the real context (mirror a sibling service test's fixture: merchant + account + intent creation), covering:
  1. void of a CREATED intent → returns `VOIDED`, simulator charge reads `CANCELLED`;
  2. pay-then-void → `IntentNotVoidableException` whose `current()` is `SETTLED` and the intent really is SETTLED with the settled balance intact (drive via `payments.get` after paying);
  3. fail-then-void → `current()` is `FAILED`;
  4. void of an already-VOIDED intent → `IntentNotVoidableException` with `VOIDED`;
  5. registry pin: `nummus.intents{outcome=voided}` reaches 1.0 after (1) (the class's `SimpleMeterRegistry` field).
  Write the full file in the sibling suite's idiom; the assertions use `assertEquals`/`assertThrows` only.

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/IntentVoidServiceTest.java
git commit -m "test: cover the intent void state machine"
```

- [ ] **Step 3: Implement**

- `IntentStatus`: add `VOIDED` (update the javadoc's terminal list).
- `IntentNotVoidableException` — mirror `PaymentAccountNotActiveException`'s 409-mapped shape, carrying `(publicId, current)` with a message `"intent not voidable: <id> is <status>"`.
- `PaymentsRepository` + JDBC impl: `boolean transitionToVoided(UUID publicId);` → `guardedTransition(publicId, "VOIDED", null, null)` beside `transitionToExpired`.
- `IntentEventTypes`: `public static final String VOIDED = "payment_intent.voided";` and add to `ALL`.
- `GlobalExceptionHandler`: `@ExceptionHandler(IntentNotVoidableException.class)` → `HttpStatus.CONFLICT` beside the `PaymentAccountNotActiveException` group (or its own method mirroring it).
- `PaymentsService`: `/** Withdraws a CREATED intent; money already paid settles instead. */ PaymentIntent voidIntent(UUID merchantPublicId, UUID publicId);`
- `PaymentsServiceImpl` (mirroring the ownership + lazy-expiry preamble of `get`, and the resolver branching of `RefundsServiceImpl.get`):

```java
  @Override
  @Transactional
  public PaymentIntent voidIntent(UUID merchantPublicId, UUID publicId) {
    // Ownership precedes everything; the read's lazy expiry settles the
    // expiry question before the void guard runs.
    var intent = requireOwnedIntent(merchantPublicId, publicId);
    if (intent.status() != IntentStatus.CREATED) {
      throw new IntentNotVoidableException(publicId, intent.status());
    }
    // One attempt, post-attempt branching — the refund-resolver contract:
    // a cancel that loses to a parallel pay observes SUCCEEDED and settles.
    var after = network.cancelCharge(intent.chargePublicId());
    return switch (after.status()) {
      case CANCELLED -> {
        if (repository.transitionToVoided(publicId)) {
          var voided = repository.findByPublicId(publicId).orElseThrow();
          intentEvents.publish(toEvent(merchantPublicId, IntentEventTypes.VOIDED, voided,
              null, null));
          count("nummus.intents", "voided");
          yield voided;
        }
        yield repository.findByPublicId(publicId).orElseThrow();
      }
      // Money already moved: settle through the existing path, then reject
      // the void with the truth.
      case SUCCEEDED -> {
        settle(intent, merchantPublicId);
        throw new IntentNotVoidableException(publicId, IntentStatus.SETTLED);
      }
      case FAILED -> {
        if (repository.transitionToFailed(publicId)) {
          var failed = repository.findByPublicId(publicId).orElseThrow();
          intentEvents.publish(toEvent(merchantPublicId, IntentEventTypes.FAILED, failed,
              null, null));
          count("nummus.intents", "failed");
        }
        throw new IntentNotVoidableException(publicId, IntentStatus.FAILED);
      }
      // PENDING cannot be observed post-attempt: cancelCharge either won
      // (CANCELLED) or lost to a terminal state.
      default -> throw new IntentNotVoidableException(publicId, intent.status());
    };
  }
```

Adapt `requireOwnedIntent` to how `get` actually performs the ownership read (extract or inline the same lines — including the lazy expiry block — so void composes with it; keep `get`'s behavior byte-identical).

- In-memory fakes: `transitionToVoided` mirroring their `transitionToExpired`; charge maps gain cancel support if the fake network is used by this suite (it is not — the service test runs the real simulator).

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java
git commit -m "feat: void created intents with post-attempt branching"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest='IntentVoidServiceTest,ModuleBoundaryTest'` on megalan. Expected: PASS.

---

### Task 3: The REST surface

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/payments/interfaces/PaymentsController.java`
- Test: `src/test/java/com/leandrossb/nummus/payments/IntentVoidRestApiTest.java`

**Interfaces:**
- Consumes: Task 2's `voidIntent`; `IntentResponse` composition (`toResponse`).
- Produces: `POST /v1/payment-intents/{id}/void` → `200` + `IntentResponse`; 409 with state on terminal; 404 foreign; idempotent replay.

- [ ] **Step 1: Write the failing REST test** — sibling idiom of `RefundsRestApiTest` (merchant fixture, `KEY = "Idempotency-Key"`), covering:
  1. void CREATED → 200, `$.status` `VOIDED`; the M22 listing shows it `VOIDED`;
  2. pay-then-void → 409 with the problem detail mentioning settled;
  3. void twice → second is 409 (idempotent REPLAY returns the stored 200 — use a fresh `Idempotency-Key` for the second call to assert the 409; a same-key retry replays 200);
  4. foreign intent → 404;
  5. the `payment_intent.voided` event is deliverable: register a webhook endpoint (ApiDrivers.loopbackUrl), void, then assert the outbox holds the event (mirror an existing webhook-event assertion test's approach — find one and copy its probe).

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/IntentVoidRestApiTest.java
git commit -m "test: cover the intent void REST surface"
```

- [ ] **Step 3: Implement the controller endpoint**

```java
  @Idempotent
  @PostMapping("/{id}/void")
  IntentResponse voidIntent(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return toResponse(merchant, payments.voidIntent(merchant.merchantPublicId(), id));
  }
```

(`void` is a reserved word — the method name is `voidIntent`; add nothing else.)

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/payments/interfaces/PaymentsController.java
git commit -m "feat: expose intent void over REST"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=IntentVoidRestApiTest` on megalan. Expected: PASS.

---

### Task 4: README — capability and status entries

**Files:**
- Modify: `README.md`

**Interfaces:** Consumes Tasks 1–3. Produces documentation only.

- [ ] **Step 1: Update the two README sections**

In the Capabilities table, **Instant payments** row: after "create, expire, settle;", insert "void before payment," (keep the rest unchanged).

In the Status list, append after the M24 line:

```markdown
- [x] M25 — Merchant intent void
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mark M25 merchant intent void complete"
```

- [ ] **Step 3: Controller verifies**

Full suite on megalan (`./mvnw -B verify` via the Global Constraints command, minus `-Dtest`). Expected: BUILD SUCCESS, Failures: 0, Errors: 0.
