package com.leandrossb.nummus.conciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.conciliation.application.ConciliationWorker;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The scheduled tumbling window, driven by calling the worker bean directly —
 * the poll and initial delays are pinned to an hour in the base class, so no
 * scheduler fires underneath the assertions. The window starts at the
 * self-healing marker (manual ingests that covered pending territory are never
 * re-covered), ends at the lagged DB clock, an empty window persists nothing
 * but still advances the marker, and report + digest + advance commit together.
 */
@TestMethodOrder(OrderAnnotation.class)
@AutoConfigureMockMvc
class ConciliationWorkerTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  /** Settled intents this class creates — backdated in {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private ConciliationWorker worker;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  @BeforeAll
  static void clearFutureWindowsFromOtherSuites() throws Exception {
    // Earlier suites ingest manual windows that reach 60s into the future; their
    // period_to would otherwise hold the self-healing start past the lagged now
    // and no-op every tick of this class. Backdate the stragglers — the same
    // DB-side rewrite the other conciliation suites apply to their fixtures.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("update conciliation.settlement_report"
          + " set period_from = period_from - interval '2 hours',"
          + " period_to = period_to - interval '2 hours' where period_to > now()");
    }
  }

  @Test
  @Order(1)
  void emptyWindowWritesNoReportAndAdvancesTheMarker() throws Exception {
    // Plant the marker half a second behind the lagged now: the tick's window
    // is [now-30.5s, now-30s] — provably free of this class's fixtures and, at
    // half a second wide, of every other suite's too.
    setLastWindowEnd("now() - interval '30.5 seconds'");
    Instant before = lastWindowEnd();
    int reports = reportCount();
    worker.tick();
    Assertions.assertEquals(reports, reportCount(), "quiet window must not spam reports");
    Instant after = lastWindowEnd();
    Assertions.assertTrue(after.isAfter(before), "marker advances even on an empty window");
  }

  @Test
  @Order(2)
  void openWindowWritesReportEventAndAdvanceTogether() throws Exception {
    // One operator endpoint to observe the digest.
    String endpointPublicId = registerReportOpenEndpoint();
    // A divergence in the live window: settle internally, hide externally, and
    // park the settlement behind the lag so this tick's window covers it.
    settleAndHideExternalCharge();
    setLastWindowEnd("now() - interval '90 seconds'");
    int eventsBefore = countReportOpenEvents();
    int deliveriesBefore = deliveriesForEndpoint(endpointPublicId);
    int reports = reportCount();
    worker.tick();

    Assertions.assertEquals(reports + 1, reportCount(), "the divergence must be ingested");
    Assertions.assertEquals(eventsBefore + 1, countReportOpenEvents());
    Assertions.assertEquals(deliveriesBefore + 1, deliveriesForEndpoint(endpointPublicId));
    // The window end honors the lag: the new marker sits at least ~29s behind now.
    Assertions.assertTrue(dbNow().isAfter(lastWindowEnd().plusSeconds(29)),
        "window end must hold back the lag");
  }

  @Test
  @Order(3)
  void manualOverlapThenTickStartsAfterTheManualReport() throws Exception {
    UUID hidden = settleAndHideExternalCharge();
    setLastWindowEnd("now() - interval '90 seconds'");
    // Manual wide ingest covering everything up to now.
    String from = Instant.now().minusSeconds(3600).toString();
    String to = Instant.now().toString();
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isCreated());
    int manualReports = reportCount();

    worker.tick();

    // The manual report covered pending territory; the scheduler must start
    // after its period_to, ingest nothing new, and write no second report
    // over the same ground — this class's own divergence stays recorded once.
    Assertions.assertTrue(reportCount() <= manualReports + 1,
        "no re-coverage of the manual window");
    Assertions.assertEquals(1, lineCountForCharge(hidden),
        "no charge the scheduler already covered may appear in another report");
  }

  @Test
  @Order(4)
  void tickIsANoOpWhenTheMarkerIsAheadOfTheLaggedNow() throws Exception {
    // Push the marker into the future: end <= start must short-circuit.
    setLastWindowEnd("now() + interval '1 hour'");
    Instant pushed = lastWindowEnd();
    worker.tick();
    Assertions.assertEquals(pushed, lastWindowEnd());
  }

  /** Port 9 (discard): a loopback URL the URL policy accepts that nothing
   *  ever contacts — the same trick ConciliationAlertsTest uses. */
  private String registerReportOpenEndpoint() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://127.0.0.1:9/worker-" + UUID.randomUUID()
                + "\",\"eventTypes\":[\"conciliation.report_open\"]}"))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(created.getResponse().getContentAsString(), "$.publicId");
  }

  /** MISSING_EXTERNAL by construction, parked inside the worker window: the
   *  settlement is internal-only (the simulator charge row is deleted) and the
   *  intent's settled_at is held 40 seconds back — behind the 30s window lag,
   *  ahead of any marker these tests plant. Merchant → account → intent →
   *  simulator pay → GET settles: the same recipe as ConciliationAlertsTest. */
  private UUID settleAndHideExternalCharge() throws Exception {
    MvcResult merchant = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Worker Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String bearer = "Bearer "
        + JsonPath.read(merchant.getResponse().getContentAsString(), "$.apiKey.secret");
    MvcResult opened = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Worker Holder\"}"))
        .andExpect(status().isCreated()).andReturn();
    String accountLocation = opened.getResponse().getHeader("Location");
    MvcResult intent = mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId(accountLocation) + "\",\"amount\":7.0000}"))
        .andExpect(status().isCreated()).andReturn();
    String intentLocation = intent.getResponse().getHeader("Location");
    UUID intentPublicId = UUID.fromString(accountId(intentLocation));
    settledIntents.add(intentPublicId);
    String chargeId = JsonPath.read(mockMvc
        .perform(get(intentLocation).header("Authorization", bearer))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.chargeId");
    mockMvc.perform(post("/simulator/charges/" + chargeId + "/pay")).andExpect(status().isOk());
    mockMvc.perform(get(intentLocation).header("Authorization", bearer))
        .andExpect(status().isOk());
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate(
          "delete from psp_simulator.charge where public_id = '" + chargeId + "'");
      st.executeUpdate("update payments.payment_intent set settled_at = now() - interval '40 seconds'"
          + " where public_id = '" + intentPublicId + "'");
    }
    return UUID.fromString(chargeId);
  }

  private static String accountId(String location) {
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private void setLastWindowEnd(String sqlExpression) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update conciliation.ingest_state set last_window_end = " + sqlExpression
          + ", updated_at = now() where id = 1");
    }
  }

  private Instant dbNow() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select now()")) {
      rs.next();
      return rs.getTimestamp(1).toInstant();
    }
  }

  private Instant lastWindowEnd() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select last_window_end from conciliation.ingest_state "
            + "where id = 1")) {
      rs.next();
      return rs.getTimestamp(1).toInstant();
    }
  }

  private int reportCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from conciliation.settlement_report")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private int countReportOpenEvents() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from webhooks.webhook_event "
            + "where type = 'conciliation.report_open'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** Digest deliveries fanned out to exactly this operator endpoint. */
  private int deliveriesForEndpoint(String endpointPublicId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from webhooks.webhook_delivery d "
            + "join webhooks.webhook_endpoint p on p.id = d.endpoint_id "
            + "join webhooks.webhook_event e on e.id = d.event_id "
            + "where p.public_id = '" + endpointPublicId + "' "
            + "and e.type = 'conciliation.report_open'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private int lineCountForCharge(UUID chargePublicId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from conciliation.report_line "
            + "where charge_public_id = '" + chargePublicId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /**
   * The container is shared across classes and other suites assert over
   * now-relative windows. Push this class's settlements two hours back — the
   * same DB-side rewrite ConciliationAlertsTest uses — so they never fall
   * inside another test's window (a stray MISSING_EXTERNAL would turn its
   * clean ingests OPEN). The hidden network evidence is deleted, not backdated.
   */
  @AfterAll
  static void moveFixturesOutOfNowWindows() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      if (!settledIntents.isEmpty()) {
        st.executeUpdate("UPDATE payments.payment_intent SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledIntents) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(java.util.stream.Collectors.joining(","));
  }
}
