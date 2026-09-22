package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class WebhookDeliveriesPaginationTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private OperatorKeysService operatorKeys;

  private record Merchant(String key, String merchantId) {}

  private Merchant newMerchant() throws Exception {
    var created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKeys.create("probe", null).secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"pagination probe\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    return new Merchant(JsonPath.read(body, "$.apiKey.secret"), JsonPath.read(body, "$.merchantId"));
  }

  /** One endpoint owned by the merchant with n FAILED deliveries (highest ids first on read). */
  private String seedDeliveries(Merchant merchant, int n) throws Exception {
    String endpointId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, merchant_public_id, url, secret) "
          + "values ('" + endpointId + "', '" + merchant.merchantId() + "', 'http://127.0.0.1:9/hook', 's1')");
      for (int i = 0; i < n; i++) {
        String eventId = UUID.randomUUID().toString();
        st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
            + "values ('" + eventId + "', 'probe.evt', '{}', now())");
        st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id, status, attempts, next_attempt_at, last_attempt_at) "
            + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
            + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "'), "
            + "'FAILED', 8, now(), now()");
      }
    }
    return endpointId;
  }

  private MvcResult page(Merchant merchant, String endpointId, String query) throws Exception {
    return mockMvc.perform(get("/v1/webhook-endpoints/" + endpointId + "/deliveries" + query)
            .header("Authorization", "Bearer " + merchant.key()))
        .andExpect(status().isOk()).andReturn();
  }

  @Test
  void cursorWalkCompletes() throws Exception {
    var merchant = newMerchant();
    String endpointId = seedDeliveries(merchant, 7);
    Set<String> seen = new HashSet<>();
    String query = "?limit=3";
    int pages = 0;
    while (query != null) {
      var result = page(merchant, endpointId, query);
      List<String> ids = JsonPath.read(result.getResponse().getContentAsString(), "$[*].deliveryId");
      seen.addAll(ids);
      String cursor = result.getResponse().getHeader("Next-Cursor");
      pages++;
      query = cursor == null ? null : "?limit=3&after=" + cursor;
      if (cursor == null) {
        assertEquals(1, ids.size()); // 7 = 3 + 3 + 1
      }
    }
    assertEquals(3, pages);
    assertEquals(7, seen.size()); // distinct, complete
  }

  @Test
  void afterCombinesWithStatusFilter() throws Exception {
    var merchant = newMerchant();
    String endpointId = seedDeliveries(merchant, 5);
    // Everything is FAILED here; flip the newest two to SUCCEEDED via ids.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update webhooks.webhook_delivery set status = 'SUCCEEDED' "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "') "
          + "and id in (select id from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "') order by id desc limit 2)");
    }
    var first = page(merchant, endpointId, "?status=FAILED&limit=2");
    List<String> ids = JsonPath.read(first.getResponse().getContentAsString(), "$[*].deliveryId");
    assertEquals(2, ids.size());
    var second = page(merchant, endpointId,
        "?status=FAILED&limit=2&after=" + first.getResponse().getHeader("Next-Cursor"));
    List<String> rest = JsonPath.read(second.getResponse().getContentAsString(), "$[*].deliveryId");
    assertEquals(1, rest.size()); // 3 FAILED total: 2 + 1
    assertFalse(rest.get(0).equals(ids.get(0)));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    var merchant = newMerchant();
    String endpointId = seedDeliveries(merchant, 2);
    var result = page(merchant, endpointId, "?after=" + UUID.randomUUID());
    assertEquals("[]", result.getResponse().getContentAsString());
    assertTrue(result.getResponse().getHeader("Next-Cursor") == null);
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    var merchant = newMerchant();
    String endpointId = seedDeliveries(merchant, 1);
    mockMvc.perform(get("/v1/webhook-endpoints/" + endpointId + "/deliveries?limit=0")
            .header("Authorization", "Bearer " + merchant.key()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
    mockMvc.perform(get("/v1/webhook-endpoints/" + endpointId + "/deliveries?limit=101")
            .header("Authorization", "Bearer " + merchant.key()))
        .andExpect(status().isBadRequest());
  }
}
