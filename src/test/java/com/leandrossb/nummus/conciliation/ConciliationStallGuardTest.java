package com.leandrossb.nummus.conciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.leandrossb.nummus.conciliation.application.ConciliationWorker;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The M12-review stall hazard, closed from both ends: the manual ingest route
 * rejects a window end more than {@code nummus.conciliation.max-window-ahead}
 * into the future (no report row), and a tick whose window is fully held back
 * by a future-dated report warns once per stall episode — not once per tick.
 */
@AutoConfigureMockMvc
class ConciliationStallGuardTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private ConciliationWorker worker;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private int reportCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        var rs = st.executeQuery("select count(*) from conciliation.settlement_report")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void futureDatedWindowEndIsRejectedWithoutARow() throws Exception {
    int before = reportCount();
    String to = Instant.now().plusSeconds(3600).toString();
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + Instant.now().minusSeconds(60) + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isBadRequest());
    Assertions.assertEquals(before, reportCount());
  }

  @Test
  void withinSlackWindowEndsAreAccepted() throws Exception {
    // 30s ahead is inside the default PT5M slack: not a 400 (the window's own
    // semantics decide the rest).
    String to = Instant.now().plusSeconds(30).toString();
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + Instant.now().minusSeconds(60) + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void stalledTicksWarnOncePerEpisode() throws Exception {
    // Plant a future-dated report directly (the route guard now blocks the
    // HTTP path; SQL is how a legacy row would exist).
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into conciliation.settlement_report "
          + "(public_id, period_from, period_to, status, matched_count, amount_mismatched_count, "
          + "missing_internal_count, missing_external_count) values ('"
          + UUID.randomUUID() + "', now() - interval '1 minute', now() + interval '1 hour', "
          + "'CONCILED', 0, 0, 0, 0)");
    }
    Logger workerLogger = (Logger) LoggerFactory.getLogger(ConciliationWorker.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    workerLogger.addAppender(appender);
    try {
      worker.tick();
      worker.tick();
    } finally {
      workerLogger.detachAppender(appender);
    }
    long stalls = appender.list.stream()
        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
        .filter(e -> e.getFormattedMessage().contains("period_to")).count();
    Assertions.assertEquals(1, stalls, "one warn per stall episode, not per tick");
    // Clean up: backdate the planted row so later suites are unaffected.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update conciliation.settlement_report set period_to = now() - interval '2 hours' "
          + "where period_to > now()");
    }
  }
}
