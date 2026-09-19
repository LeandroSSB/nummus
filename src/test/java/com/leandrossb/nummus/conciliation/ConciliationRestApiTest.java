package com.leandrossb.nummus.conciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ConciliationRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  /** Fixtures this class settles/charges — backdated in {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private SimulatorService simulator;

  private String ingest(String from, String to) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
        .andReturn();
    return result.getResponse().getContentAsString();
  }

  @Test
  void settledIntentsConcileAndReplayIdempotently() throws Exception {
    // Scoped window: everything this test settles lands after `start`, and the
    // 30s back-margin absorbs DB-lag on the simulator's `updated_at` (DB clock)
    // relative to this JVM clock. Other classes' now-window fixtures cannot
    // leak in, whatever order JUnit runs methods or classes in.
    Instant start = Instant.now();
    var account = accountsService.open(new OpenAccountCommand("Concile Merchant"));
    var first = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("11.0000"), null));
    var second = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("12.0000"), null));
    settledIntents.add(first.publicId());
    settledIntents.add(second.publicId());
    networkCharges.add(first.chargePublicId());
    networkCharges.add(second.chargePublicId());
    simulator.pay(first.chargePublicId());
    simulator.pay(second.chargePublicId());
    payments.get(first.publicId());
    payments.get(second.publicId());

    String from = start.minusSeconds(30).toString();
    String to = Instant.now().plusSeconds(60).toString();
    String body = "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    var created = mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CONCILED"))
        .andExpect(jsonPath("$.matched").value(2))
        .andExpect(jsonPath("$.missingExternal").value(0))
        .andReturn().getResponse().getContentAsString();
    String reportId = com.jayway.jsonpath.JsonPath.read(created, "$.reportId");

    // A fresh key executes a fresh ingest; replaying THAT key returns its stored
    // response verbatim (idempotency is key-scoped, not body-scoped).
    String replayKey = UUID.randomUUID().toString();
    String executed = mockMvc.perform(post("/v1/conciliation/reports").header(KEY, replayKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String executedReportId = com.jayway.jsonpath.JsonPath.read(executed, "$.reportId");
    mockMvc.perform(post("/v1/conciliation/reports").header(KEY, replayKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.reportId").value(executedReportId));

    mockMvc.perform(get("/v1/conciliation/reports"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].reportId").value(executedReportId));
    mockMvc.perform(get("/v1/conciliation/reports/" + reportId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'MATCHED')]").isNotEmpty());
  }

  @Test
  void divergencesSurfacePerLine() throws Exception {
    // Same scoping as the happy path: only fixtures created from here on are
    // asserted, so an earlier method's settlements may add MATCHED lines but
    // cannot fabricate or hide this test's divergences.
    Instant start = Instant.now();
    var account = accountsService.open(new OpenAccountCommand("Divergence Merchant"));
    // MISSING_INTERNAL: the network settled a charge no intent knows about.
    var orphan = simulator.create(Money.ofBrl("77.0000"));
    networkCharges.add(orphan.publicId());
    simulator.pay(orphan.publicId());
    // AMOUNT_MISMATCH: settle internally, then tamper the network's amount DB-side.
    var tampered = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("20.0000"), null));
    settledIntents.add(tampered.publicId());
    networkCharges.add(tampered.chargePublicId());
    simulator.pay(tampered.chargePublicId());
    payments.get(tampered.publicId());
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.charge SET amount = amount + 1"
          + " WHERE public_id = '" + tampered.chargePublicId() + "'");
    }
    // MISSING_EXTERNAL: the intent settles inside the window, but the network's
    // line for its charge sits outside the report window (DB-side rewrite of the
    // charge's settlement timestamp only — the intent's settled_at stays now).
    var excluded = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("30.0000"), null));
    settledIntents.add(excluded.publicId());
    networkCharges.add(excluded.chargePublicId());
    simulator.pay(excluded.chargePublicId());
    payments.get(excluded.publicId());
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.charge SET updated_at = now() - interval '2 hours'"
          + " WHERE public_id = '" + excluded.chargePublicId() + "'");
    }

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'MISSING_INTERNAL')]").isNotEmpty())
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'AMOUNT_MISMATCH')]").isNotEmpty())
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'MISSING_EXTERNAL')]").isNotEmpty());
  }

  @Test
  void unknownReportIs404AndInvertedWindowIs400() throws Exception {
    mockMvc.perform(get("/v1/conciliation/reports/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"2026-09-18T11:00:00Z\",\"to\":\"2026-09-18T10:00:00Z\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"not-an-instant\",\"to\":\"2026-09-18T11:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void postRequiresAnIdempotencyKey() throws Exception {
    mockMvc.perform(post("/v1/conciliation/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"2026-09-18T10:00:00Z\",\"to\":\"2026-09-18T11:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * The container is shared across classes and later suites (settlement query,
   * simulator report) assert over now-relative windows. Push this class's
   * settlements and network charges two hours back — the same DB-side rewrite
   * the divergence setup uses — so they never fall inside another test's window.
   */
  @AfterAll
  static void moveFixturesOutOfNowWindows() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      if (!settledIntents.isEmpty()) {
        st.executeUpdate("UPDATE payments.payment_intent SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledIntents) + ")");
      }
      if (!networkCharges.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(networkCharges) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
