package com.leandrossb.nummus.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.conciliation.application.ConciliationWorker;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * The M15 recording contract for the remaining operator surfaces: operator
 * webhook endpoint registration/deletion, FAILED-delivery redrive, and manual
 * conciliation ingest land in {@code audit.operator_action} attributed to the
 * calling key, inside the action's own transaction. The negatives are pinned
 * too — a fee change keeps its own attribution log and records nothing, and
 * the scheduled tick has no operator, so it records nothing.
 */
@TestMethodOrder(OrderAnnotation.class)
@AutoConfigureMockMvc
class SurfaceAuditTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  /** Fixtures this class settles — backdated in {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private WebhookStore webhookStore;

  @Autowired
  private ConciliationWorker worker;

  /** (actorKey, action, subjectId) of the latest entries, newest first. */
  private List<String[]> recentEntries(int limit) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select actor_key::text, action, "
            + "coalesce(subject_id::text, '') from audit.operator_action "
            + "order by id desc limit " + limit)) {
      List<String[]> out = new ArrayList<>();
      while (rs.next()) {
        out.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
      }
      return out;
    }
  }

  private int entryCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from audit.operator_action")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private int countAction(String action) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from audit.operator_action "
            + "where action = '" + action + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** The newest detail recorded for an action. */
  private String latestDetail(String action) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select detail::text from audit.operator_action "
            + "where action = '" + action + "' order by id desc limit 1")) {
      Assertions.assertTrue(rs.next(), "expected a recorded " + action + " entry");
      return rs.getString(1);
    }
  }

  /** Registers an operator endpoint as the given bearer; returns its public id. */
  private String registerOperatorEndpoint(String bearer, String tag) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + ApiDrivers.loopbackUrl(tag) + "\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(created.getResponse().getContentAsString(), "$.publicId");
  }

  /** A settled intent and its network charge: merchant → account → intent →
   *  simulator pay → GET settles — the ConciliationAlertsTest recipe. */
  private record SettledPayment(UUID intentId, UUID chargeId) {
  }

  private SettledPayment settlePayment(String merchantName, String holderName) throws Exception {
    String bearer = "Bearer " + ApiDrivers.createMerchantAndGetKey(
        mockMvc, ApiDrivers.operatorAuth(operatorKeys), merchantName);
    MvcResult opened = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"" + holderName + "\"}"))
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
    UUID intentId = UUID.fromString(
        intentLocation.substring(intentLocation.lastIndexOf('/') + 1));
    settledIntents.add(intentId);
    String chargeId = JsonPath.read(mockMvc
        .perform(get(intentLocation).header("Authorization", bearer))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.chargeId");
    mockMvc.perform(post("/simulator/charges/" + chargeId + "/pay")).andExpect(status().isOk());
    mockMvc.perform(get(intentLocation).header("Authorization", bearer))
        .andExpect(status().isOk());
    networkCharges.add(UUID.fromString(chargeId));
    return new SettledPayment(intentId, UUID.fromString(chargeId));
  }

  /** Reports currently persisted — proves a tick actually ingested. */
  private int reportCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from conciliation.settlement_report")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private void setLastWindowEnd(String sqlExpression) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update conciliation.ingest_state set last_window_end = " + sqlExpression
          + ", updated_at = now() where id = 1");
    }
  }

  @Test
  @Order(1)
  void endpointRegistrationAndDeletionAreAudited() throws Exception {
    var actor = operatorKeys.create("surface-endpoints", null, null);
    String actorId = actor.key().publicId().toString();
    String url = ApiDrivers.loopbackUrl("surface");
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", "Bearer " + actor.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + url + "\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    String endpointId = JsonPath.read(created.getResponse().getContentAsString(), "$.publicId");
    var top = recentEntries(1).get(0);
    Assertions.assertEquals("operator_endpoint.registered", top[1]);
    Assertions.assertEquals(actorId, top[0], "the entry is attributed to the calling key");
    Assertions.assertEquals(endpointId, top[2]);
    Assertions.assertTrue(latestDetail("operator_endpoint.registered").contains(url),
        "the register's detail carries the URL");

    mockMvc.perform(delete("/v1/operator/webhook-endpoints/" + endpointId)
            .header("Authorization", "Bearer " + actor.secret()))
        .andExpect(status().isNoContent());
    top = recentEntries(1).get(0);
    Assertions.assertEquals("operator_endpoint.deleted", top[1]);
    Assertions.assertEquals(actorId, top[0], "the entry is attributed to the calling key");
    Assertions.assertEquals(endpointId, top[2]);
  }

  @Test
  @Order(2)
  void deliveryRedriveIsAudited() throws Exception {
    var actor = operatorKeys.create("surface-redrive", null, null);
    String actorId = actor.key().publicId().toString();
    String endpointId = registerOperatorEndpoint("Bearer " + actor.secret(), "redrive");
    webhookStore.insertEvent(UUID.randomUUID(), null, "conciliation.report_open",
        "{\"probe\":0}", Instant.now());
    MvcResult listed = mockMvc.perform(get(
            "/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", "Bearer " + actor.secret()))
        .andExpect(status().isOk()).andReturn();
    String deliveryId = JsonPath.read(listed.getResponse().getContentAsString(), "$[0].deliveryId");
    // Force terminal FAILED so redrive has something to requeue.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update webhooks.webhook_delivery set status = 'FAILED', attempts = 8 "
          + "where public_id = '" + deliveryId + "'");
    }
    mockMvc.perform(post("/v1/operator/webhook-deliveries/" + deliveryId + "/redrive")
            .header("Authorization", "Bearer " + actor.secret())
            .header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isAccepted());
    var top = recentEntries(1).get(0);
    Assertions.assertEquals("delivery.redriven", top[1]);
    Assertions.assertEquals(actorId, top[0], "the entry is attributed to the calling key");
    Assertions.assertEquals(deliveryId, top[2]);
  }

  @Test
  @Order(3)
  void manualIngestIsAuditedWithWindowBounds() throws Exception {
    var actor = operatorKeys.create("surface-ingest", null, null);
    String actorId = actor.key().publicId().toString();
    settlePayment("Surface Settle Merchant", "Surface Holder");
    Instant from = Instant.now().minus(30, ChronoUnit.SECONDS);
    Instant to = Instant.now().plus(60, ChronoUnit.SECONDS);
    MvcResult report = mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", "Bearer " + actor.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = report.getResponse().getContentAsString();
    Assertions.assertEquals("CONCILED", JsonPath.read(body, "$.status"),
        "a clean settle keeps the ingest CONCILED");
    String reportId = JsonPath.read(body, "$.reportId");
    var top = recentEntries(1).get(0);
    Assertions.assertEquals("conciliation.ingested", top[1]);
    Assertions.assertEquals(actorId, top[0], "the entry is attributed to the calling key");
    Assertions.assertEquals(reportId, top[2]);
    String detail = latestDetail("conciliation.ingested");
    Assertions.assertTrue(detail.contains(from.toString()), "the detail carries the window start");
    Assertions.assertTrue(detail.contains(to.toString()), "the detail carries the window end");
  }

  @Test
  @Order(4)
  void feeChangesRecordNothing() throws Exception {
    var actor = operatorKeys.create("surface-fee", null, null);
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + actor.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Surface Fee Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String merchantId = JsonPath.read(created.getResponse().getContentAsString(), "$.merchantId");
    int before = entryCount();
    mockMvc.perform(put("/v1/merchants/" + merchantId + "/fee")
            .header("Authorization", "Bearer " + actor.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rate\":0.0099,\"fixedAmount\":0.39}"))
        .andExpect(status().isOk());
    Assertions.assertEquals(before, entryCount(),
        "a fee change keeps its own attribution log (fee_schedule_entry) and records nothing here");
  }

  @Test
  @Order(5)
  void scheduledTicksRecordNothing() throws Exception {
    // This class's manual ingest (and any earlier suite) left reports whose
    // period_to reaches past now; they would hold the self-healing start past
    // the lagged now and no-op the tick. Backdate the stragglers — the same
    // DB-side rewrite ConciliationWorkerTest applies before pinning a marker.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update conciliation.settlement_report"
          + " set period_from = period_from - interval '2 hours',"
          + " period_to = period_to - interval '2 hours' where period_to > now()");
    }
    // A divergence in the live window: settle internally, hide externally, and
    // park the settlement behind the lag so this tick's window covers it.
    SettledPayment settled = settlePayment("Surface Tick Merchant", "Tick Holder");
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate(
          "delete from psp_simulator.charge where public_id = '" + settled.chargeId() + "'");
      st.executeUpdate("update payments.payment_intent set settled_at = now() - interval '40 seconds'"
          + " where public_id = '" + settled.intentId() + "'");
    }
    networkCharges.remove(settled.chargeId());
    setLastWindowEnd("now() - interval '90 seconds'");
    int ingestedBefore = countAction("conciliation.ingested");
    int reports = reportCount();
    worker.tick();
    Assertions.assertEquals(reports + 1, reportCount(),
        "the divergence must be ingested, or the negative below would pass vacuously");
    Assertions.assertEquals(ingestedBefore, countAction("conciliation.ingested"),
        "the scheduled path has no operator and records nothing");
  }

  /**
   * The container is shared across classes and other suites assert over
   * now-relative windows. Push this class's settlements and network charges
   * two hours back — the same DB-side rewrite the conciliation suites use —
   * so they never fall inside another test's window. The hidden network
   * evidence is deleted, not backdated.
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
    return ids.stream().map(id -> "'" + id + "'")
        .collect(java.util.stream.Collectors.joining(","));
  }
}
