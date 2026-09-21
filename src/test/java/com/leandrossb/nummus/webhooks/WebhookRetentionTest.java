package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.WebhookProperties;
import com.leandrossb.nummus.webhooks.application.WebhookRetentionWorker;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Retention prunes only SUCCEEDED deliveries past the TTL. FAILED survives
 * regardless of age; retentionDays = 0 disables pruning entirely.
 */
@TestPropertySource(properties = "nummus.webhooks.retention-days=30")
class WebhookRetentionTest extends IntegrationTestBase {

  @Autowired
  private WebhookRetentionWorker worker; // component-scanned, retentionDays=30 via the property
  @Autowired
  private WebhookStore store;

  @Test
  void agedSucceededArePrunedAndNothingElse() throws Exception {
    String endpointId = UUID.randomUUID().toString();
    seedEndpoint(endpointId);
    seed(endpointId, "SUCCEEDED", "40 days"); // aged — must go
    seed(endpointId, "FAILED", "40 days");    // aged but FAILED — must stay
    seed(endpointId, "SUCCEEDED", "1 day");   // fresh — must stay

    worker.prune();

    assertEquals(1, count(endpointId, "SUCCEEDED")); // the fresh one only
    assertEquals(1, count(endpointId, "FAILED"));
  }

  @Test
  void zeroRetentionDisablesPruning() throws Exception {
    var disabled = new WebhookRetentionWorker(store,
        new WebhookProperties(8, Duration.ofSeconds(2), 50, 0));
    String endpointId = UUID.randomUUID().toString();
    seedEndpoint(endpointId);
    seed(endpointId, "SUCCEEDED", "40 days");

    disabled.prune();

    assertEquals(1, count(endpointId, "SUCCEEDED"));
  }

  @Test
  void pruneIteratesBatchesUntilExhausted() throws Exception {
    String endpointId = UUID.randomUUID().toString();
    seedEndpoint(endpointId);
    seed(endpointId, "SUCCEEDED", "40 days");
    seed(endpointId, "SUCCEEDED", "40 days");
    seed(endpointId, "SUCCEEDED", "40 days");

    int deleted = store.pruneSucceededBefore(Instant.now().minus(Duration.ofDays(30)), 2);

    assertEquals(3, deleted);
    assertEquals(0, count(endpointId, "SUCCEEDED"));
  }

  private void seedEndpoint(String endpointId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
          + "values ('" + endpointId + "', 'http://127.0.0.1:9/hook', 's1')");
    }
  }

  /** One event per delivery — the unique (event_id, endpoint_id) constraint demands it. */
  private void seed(String endpointId, String status, String age) throws Exception {
    String eventId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('" + eventId + "', 'probe.evt', '{}', now())");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id, status, attempts, next_attempt_at, last_attempt_at) "
          + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
          + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "'), "
          + "'" + status + "', 1, now(), now() - interval '" + age + "'");
    }
  }

  private int count(String endpointId, String status) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      var rs = st.executeQuery("select count(*) from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "') and status = '" + status + "'");
      rs.next();
      return rs.getInt(1);
    }
  }
}
