package com.leandrossb.nummus.conciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
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
 * Every OPEN ingest pushes one {@code conciliation.report_open} digest into
 * the webhook outbox inside the ingest transaction: fan-out reaches operator
 * endpoints only (NULL audience), one delivery per subscribed endpoint. A
 * CONCILED or rejected ingest publishes nothing.
 */
@TestMethodOrder(OrderAnnotation.class)
@AutoConfigureMockMvc
class ConciliationAlertsTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  /** Fixtures this class settles/charges — backdated in {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  /** Port 9 (discard): a loopback URL the URL policy accepts that nothing
   *  ever contacts. Unique path per registration keeps fixtures disjoint. */
  private String registerOperatorEndpoint(String typesJson) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://127.0.0.1:9/alerts-" + UUID.randomUUID()
                + "\",\"eventTypes\":" + typesJson + "}"))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(created.getResponse().getContentAsString(), "$.publicId");
  }

  /** Merchant → account → intent → simulator pay → GET settles. Returns the
   *  network charge's public id — the external evidence of the settlement. */
  private UUID settlePayment() throws Exception {
    MvcResult merchant = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Alert Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String bearer = "Bearer "
        + JsonPath.read(merchant.getResponse().getContentAsString(), "$.apiKey.secret");
    MvcResult opened = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Alert Holder\"}"))
        .andExpect(status().isCreated()).andReturn();
    String accountLocation = opened.getResponse().getHeader("Location");
    String accountId = accountLocation.substring(accountLocation.lastIndexOf('/') + 1);
    MvcResult intent = mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":7.0000}"))
        .andExpect(status().isCreated()).andReturn();
    String intentLocation = intent.getResponse().getHeader("Location");
    settledIntents.add(UUID.fromString(
        intentLocation.substring(intentLocation.lastIndexOf('/') + 1)));
    String chargeId = JsonPath.read(mockMvc
        .perform(get(intentLocation).header("Authorization", bearer))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.chargeId");
    mockMvc.perform(post("/simulator/charges/" + chargeId + "/pay")).andExpect(status().isOk());
    mockMvc.perform(get(intentLocation).header("Authorization", bearer))
        .andExpect(status().isOk());
    networkCharges.add(UUID.fromString(chargeId));
    return UUID.fromString(chargeId);
  }

  /** MISSING_EXTERNAL by construction: the settlement is internal-only now. */
  private void hideExternalCharge(UUID chargeId) throws Exception {
    networkCharges.remove(chargeId);
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("delete from psp_simulator.charge where public_id = '" + chargeId + "'");
    }
  }

  private int reportOpenEventCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from webhooks.webhook_event "
            + "where type = 'conciliation.report_open'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** Digest deliveries fanned out to exactly these operator endpoints. */
  private int deliveriesForEndpoints(List<String> endpointPublicIds) throws Exception {
    String ids = endpointPublicIds.stream().map(id -> "'" + id + "'")
        .collect(Collectors.joining(","));
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from webhooks.webhook_delivery d "
            + "join webhooks.webhook_endpoint p on p.id = d.endpoint_id "
            + "join webhooks.webhook_event e on e.id = d.event_id "
            + "where p.public_id in (" + ids + ") and e.type = 'conciliation.report_open'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** The most recent digest payload — the caller pins the envelope shape. */
  private String latestReportOpenPayload() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select payload from webhooks.webhook_event "
            + "where type = 'conciliation.report_open' order by id desc limit 1")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private MvcResult ingestWindow(Instant from, Instant to) throws Exception {
    return mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isCreated()).andReturn();
  }

  /** Runs first so its window sees only its own fixtures: a clean settle
   *  (external evidence intact) ingests CONCILED and publishes nothing. */
  @Test
  @Order(1)
  void conciledIngestEmitsNothing() throws Exception {
    int eventsBefore = reportOpenEventCount();
    settlePayment();

    MvcResult report = ingestWindow(Instant.now().minus(30, ChronoUnit.SECONDS),
        Instant.now().plus(60, ChronoUnit.SECONDS));
    String body = report.getResponse().getContentAsString();
    Assertions.assertEquals("CONCILED", JsonPath.read(body, "$.status"));
    Assertions.assertEquals(0, ((Number) JsonPath.read(body, "$.missingExternal")).intValue());

    Assertions.assertEquals(eventsBefore, reportOpenEventCount());
  }

  @Test
  @Order(2)
  void openIngestPushesOneDigestPerSubscribedOperatorEndpoint() throws Exception {
    int eventsBefore = reportOpenEventCount();
    String typed = registerOperatorEndpoint("[\"conciliation.report_open\"]");
    String allTypes = registerOperatorEndpoint("[]"); // all types — also subscribed
    hideExternalCharge(settlePayment());

    MvcResult report = ingestWindow(Instant.now().minus(30, ChronoUnit.SECONDS),
        Instant.now().plus(60, ChronoUnit.SECONDS));
    String body = report.getResponse().getContentAsString();
    Assertions.assertEquals("OPEN", JsonPath.read(body, "$.status"));

    Assertions.assertEquals(eventsBefore + 1, reportOpenEventCount());
    Assertions.assertEquals(2, deliveriesForEndpoints(List.of(typed, allTypes)));
    // The digest carries the report id and the divergence tally — counts and
    // ids only, never amounts.
    String payload = latestReportOpenPayload();
    Assertions.assertEquals("conciliation.report_open", JsonPath.read(payload, "$.type"));
    Assertions.assertEquals((String) JsonPath.read(body, "$.reportId"),
        (String) JsonPath.read(payload, "$.data.reportId"));
    Assertions.assertEquals(1,
        ((Number) JsonPath.read(payload, "$.data.missingExternal")).intValue());
    Assertions.assertEquals(Set.of("reportId", "from", "to", "matched", "amountMismatched",
        "missingInternal", "missingExternal"), ((Map<?, ?>) JsonPath.read(payload, "$.data")).keySet());
  }

  @Test
  @Order(3)
  void rejectedIngestEmitsNothing() throws Exception {
    int before = reportOpenEventCount();
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + Instant.now() + "\",\"to\":\""
                + Instant.now().minusSeconds(60) + "\"}"))
        .andExpect(status().isBadRequest());
    Assertions.assertEquals(before, reportOpenEventCount());
  }

  /**
   * The container is shared across classes and other suites assert over
   * now-relative windows. Push this class's settlements and network charges
   * two hours back — the same DB-side rewrite ConciliationRestApiTest uses —
   * so they never fall inside another test's window.
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
