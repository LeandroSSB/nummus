package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
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

/** Schedule neutralized (same properties keys as the worker test, also pinned by
 *  {@code IntegrationTestBase}; restated here to document intent — the nested
 *  @TestConfiguration there splits the contexts). */
@SpringBootTest(properties = {
    "nummus.webhooks.poll-delay-ms=3600000",
    "nummus.webhooks.initial-delay-ms=3600000"})
class WebhookDeliveryClientTest extends IntegrationTestBase {

  @Autowired
  private EventDeliveryClient client;

  @Autowired
  private WebhookStore store;

  @Autowired
  private WebhookDeliveryWorker worker;

  /**
   * The PostgreSQL container is shared across the whole suite — start clean so
   * cross-class leftovers can never be claimed by these exact-count assertions.
   * Runs per test (like {@code WebhookDeliveryWorkerTest}): {@code @BeforeAll}
   * would fire before the context boots, i.e. before Flyway has created the
   * schema on a fresh container.
   */
  @org.junit.jupiter.api.BeforeEach
  void cleanWebhooksTables() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("TRUNCATE webhooks.webhook_delivery, webhooks.webhook_event, webhooks.webhook_endpoint");
    }
  }

  @Test
  void deliversSignedByteExactRequestOverHttp() throws Exception {
    try (ReceiverServer receiver = new ReceiverServer()) {
      String payload = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"payment_intent.settled\"}";
      var result = client.deliver(URI.create(receiver.url("/hook")), "whsec_e2e",
          "payment_intent.settled", payload);

      assertTrue(result.delivered());
      assertEquals(200, result.httpStatus());
      assertEquals(1, receiver.requests.size());
      var received = receiver.requests.get(0);
      assertEquals("POST", received.method());
      assertEquals(payload, received.body()); // byte-exact stored payload
      assertEquals("payment_intent.settled", received.headers().get("Nummus-event"));
      assertTrue(received.headers().get("Nummus-signature").matches("t=\\d+,v1=[0-9a-f]{64}"),
          received.headers().get("Nummus-signature"));

      // Receiver-side verification: recompute the MAC exactly as a merchant would.
      String header = received.headers().get("Nummus-signature");
      String timestamp = header.substring(2, header.indexOf(','));
      String expected = com.leandrossb.nummus.webhooks.application.SignatureHeaders.sign(
          "whsec_e2e", payload, Instant.ofEpochSecond(Long.parseLong(timestamp)));
      assertEquals(expected, header);
    }
  }

  @Test
  void nonSuccessResponsesAreReportedNotThrown() throws Exception {
    try (ReceiverServer receiver = new ReceiverServer()) {
      receiver.respondWith("/flaky", 500);
      var result = client.deliver(URI.create(receiver.url("/flaky")), "whsec_e2e",
          "payment_intent.settled", "{}");
      assertFalse(result.delivered());
      assertEquals(500, result.httpStatus());
    }
  }

  @Test
  void endToEndSettledIntentIsDeliveredWithAValidSignature() throws Exception {
    try (ReceiverServer receiver = new ReceiverServer()) {
      var endpoint = store.insertEndpoint(new WebhookEndpoint(SeedMerchant.PUBLIC_ID, UUID.randomUUID(),
          URI.create(receiver.url("/merchant")), "whsec_flow", List.of(),
          EndpointStatus.ACTIVE, Instant.now()));
      store.insertEvent(UUID.randomUUID(), SeedMerchant.PUBLIC_ID, "payment_intent.settled",
          "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"payment_intent.settled\",\"data\":{\"amount\":\"7.0000\"}}",
          Instant.now());

      worker.deliverDue();

      assertEquals(1, receiver.requests.size());
      var received = receiver.requests.get(0);
      assertTrue(received.body().contains("\"amount\":\"7.0000\""));
      assertEquals("SUCCEEDED",
          store.listDeliveries(SeedMerchant.PUBLIC_ID, endpoint.publicId(), null, null, 50).get(0).status());
    }
  }
}
