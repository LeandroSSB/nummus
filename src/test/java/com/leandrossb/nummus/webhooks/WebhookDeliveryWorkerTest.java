package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.DeliveryResult;
import com.leandrossb.nummus.webhooks.application.EventDeliveryClient;
import com.leandrossb.nummus.webhooks.application.WebhookDeliveryWorker;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Owns a context with the schedule neutralized (poll/initial pushed to 1h). */
@SpringBootTest(properties = {
    "nummus.webhooks.poll-delay-ms=3600000",
    "nummus.webhooks.initial-delay-ms=3600000"})
class WebhookDeliveryWorkerTest extends IntegrationTestBase {

  @Autowired
  private WebhookStore store;

  @Autowired
  private WebhookDeliveryWorker worker;

  @Autowired
  private FakeClient client;

  /**
   * The PostgreSQL container is shared across the whole suite — start clean so
   * cross-class leftovers can never be claimed by these exact-count assertions.
   * Runs per test (like {@code WebhookStoreTest}): {@code @BeforeAll} would fire
   * before the context boots, i.e. before Flyway has created the schema.
   */
  @org.junit.jupiter.api.BeforeEach
  void cleanWebhooksTables() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("TRUNCATE webhooks.webhook_delivery, webhooks.webhook_event, webhooks.webhook_endpoint");
    }
  }

  @TestConfiguration
  static class FakeClientConfig {
    @Bean
    @Primary
    FakeClient fakeClient() {
      return new FakeClient();
    }
  }

  /** Records per-URL call counts — claim batches may include other tests' rows. */
  static class FakeClient implements EventDeliveryClient {
    final List<URI> urls = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean succeed;
    volatile Integer status = 200;

    long callsFor(String urlSuffix) {
      return urls.stream().filter(u -> u.toString().endsWith(urlSuffix)).count();
    }

    @Override
    public DeliveryResult deliver(URI url, String secret, String eventType, String payload) {
      urls.add(url);
      return new DeliveryResult(succeed, status);
    }
  }

  private UUID endpoint(String path) {
    // Loopback http is the policy's local-receiver class — the delivery-time
    // gate re-resolves every URL, and the reserved .example TLD does not
    // resolve, which would (correctly) fail the attempt before the fake
    // client is ever consulted.
    return store.insertEndpoint(new WebhookEndpoint(SeedMerchant.PUBLIC_ID, UUID.randomUUID(),
        URI.create("http://127.0.0.1/worker-" + path), "whsec_worker",
        List.of(), EndpointStatus.ACTIVE, Instant.now())).publicId();
  }

  private void publish() {
    store.insertEvent(UUID.randomUUID(), "payment_intent.settled", "{}", Instant.now());
  }

  @Test
  void successfulDeliveryIsRecordedOnceAndNotReclaimed() {
    var endpointId = endpoint("success");
    publish();
    client.succeed = true;

    worker.deliverDue();
    worker.deliverDue(); // nothing due anymore for this endpoint

    assertEquals(1, client.callsFor("/worker-success"));
    assertEquals(1, store.listDeliveries(SeedMerchant.PUBLIC_ID, endpointId, "SUCCEEDED", null, 50).size());
  }

  @Test
  void failedAttemptSchedulesExponentialBackoff() {
    var endpointId = endpoint("backoff");
    publish();
    client.succeed = false;
    client.status = 500;

    worker.deliverDue();
    worker.deliverDue(); // still within the backoff window — no second attempt

    assertEquals(1, client.callsFor("/worker-backoff"));
    var record = store.listDeliveries(SeedMerchant.PUBLIC_ID, endpointId, null, null, 50).get(0);
    assertEquals("PENDING", record.status());
    assertEquals(1, record.attempts());
    assertEquals(500, record.lastResponseStatus());
  }

  @Test
  void exhaustedBudgetFailsPermanently() throws Exception {
    var endpointId = endpoint("exhaust");
    publish();
    client.succeed = false;
    client.status = 503;

    // Age the row DB-side to make every retry due immediately (skew-safe).
    for (int i = 0; i < 8; i++) {
      try (var c = adminConnection(); var st = c.createStatement()) {
        st.executeUpdate("UPDATE webhooks.webhook_delivery SET next_attempt_at = now() - interval '1 second' "
            + "WHERE endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/worker-exhaust')");
      }
      worker.deliverDue();
    }

    var record = store.listDeliveries(SeedMerchant.PUBLIC_ID, endpointId, null, null, 50).get(0);
    assertEquals("FAILED", record.status());
    assertEquals(8, record.attempts());
    assertEquals(8, client.callsFor("/worker-exhaust"));
  }

  @Test
  void deletedEndpointFailsDeliveryWithoutAnHttpRequest() {
    var endpointId = endpoint("deleted");
    publish();
    store.markEndpointDeleted(SeedMerchant.PUBLIC_ID, endpointId);
    client.succeed = true;

    worker.deliverDue();

    assertEquals(0, client.callsFor("/worker-deleted"));
    assertEquals(1, store.listDeliveries(SeedMerchant.PUBLIC_ID, endpointId, "FAILED", null, 50).size());
  }
}
