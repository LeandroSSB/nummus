package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.IssuedApiKey;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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
class LimitsHistoryRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private record OperatorMerchant(String merchantId, String operatorSecret) {}

  /** Creates a merchant with a freshly minted operator key; every limits PUT
   *  this test drives afterwards is attributed to that same key. */
  private OperatorMerchant createMerchantAsOperator(String name) throws Exception {
    IssuedApiKey operatorKey = operatorKeys.create("limits-history-probe", null, null);
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKey.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    String merchantId = JsonPath.read(created.getResponse().getContentAsString(), "$.merchantId");
    return new OperatorMerchant(merchantId, operatorKey.secret());
  }

  /** PUTs a limits replacement as the merchant's acting operator key. */
  private void putLimits(OperatorMerchant acting, String intentCap, String payoutCap)
      throws Exception {
    String content = "{";
    if (intentCap != null) {
      content += "\"maxIntentAmount\":" + intentCap;
    }
    if (payoutCap != null) {
      content += (content.length() > 1 ? "," : "") + "\"maxPayoutAmount\":" + payoutCap;
    }
    mockMvc.perform(put("/v1/merchants/" + acting.merchantId() + "/limits")
            .header("Authorization", "Bearer " + acting.operatorSecret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content + "}"))
        .andExpect(status().isOk());
  }

  /** Walks the history by Next-Cursor, collecting every entryId; the walk's
   *  cursors must be distinct per non-final page or it would not terminate. */
  private List<String> walk(OperatorMerchant acting, int limit) throws Exception {
    Set<String> cursors = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(
              get("/v1/merchants/" + acting.merchantId() + "/limits-history" + query)
                  .header("Authorization", "Bearer " + acting.operatorSecret()))
          .andExpect(status().isOk()).andReturn();
      ids.addAll(JsonPath.<List<String>>read(
          result.getResponse().getContentAsString(), "$[*].entryId"));
      String cursor = result.getResponse().getHeader("Next-Cursor");
      pages++;
      query = cursor == null ? null : "?limit=" + limit + "&after=" + cursor;
      if (cursor != null) {
        cursors.add(cursor);
      }
    }
    assertEquals(pages - 1, cursors.size());
    return ids;
  }

  /** The merchant's limits entries straight from the table, newest first. */
  private List<String> newestFirstIdsForMerchant(String merchantPublicId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select e.public_id from merchants.payment_limits_entry e "
            + "join merchants.merchant m on m.id = e.merchant_id "
            + "where m.public_id = '" + merchantPublicId + "' order by e.id desc")) {
      List<String> ids = new ArrayList<>();
      while (rs.next()) {
        ids.add(rs.getObject(1, UUID.class).toString());
      }
      return ids;
    }
  }

  @Test
  void limitsHistoryWalkCoversEveryChangeExactlyOnceNewestFirst() throws Exception {
    var merchant = createMerchantAsOperator("Limits History A");
    for (int i = 1; i <= 7; i++) {
      putLimits(merchant, (i * 100) + ".0000", (i * 50) + ".0000");
    }
    var ids = walk(merchant, 3);
    assertEquals(7, ids.size());
    assertEquals(7, new HashSet<>(ids).size());
    assertEquals(newestFirstIdsForMerchant(merchant.merchantId()), ids);
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    var merchant = createMerchantAsOperator("Limits History B");
    putLimits(merchant, "10.0000", null);
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId() + "/limits-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret())
            .param("after", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0))
        .andExpect(header().doesNotExist("Next-Cursor"));
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    var merchant = createMerchantAsOperator("Limits History C");
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId() + "/limits-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret()).param("limit", "0"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId() + "/limits-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret()).param("limit", "101"))
        .andExpect(status().isBadRequest());
  }
}
