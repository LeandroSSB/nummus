package com.leandrossb.nummus.merchants;

import static org.hamcrest.Matchers.nullValue;
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
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class FeeHistoryRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private record OperatorMerchant(String merchantId, String operatorKeyPublicId,
      String operatorSecret) {}

  /** Creates a merchant with a freshly minted operator key; every fee PUT
   *  this test drives afterwards is attributed to that same key. */
  private OperatorMerchant createMerchantAsOperator(String name, String feeBody) throws Exception {
    IssuedApiKey operatorKey = operatorKeys.create("fee-probe", null, null);
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKey.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(feeBody == null ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"fee\":" + feeBody + "}"))
        .andExpect(status().isCreated()).andReturn();
    String merchantId = JsonPath.read(created.getResponse().getContentAsString(), "$.merchantId");
    return new OperatorMerchant(merchantId, operatorKey.key().publicId().toString(),
        operatorKey.secret());
  }

  /** PUTs a fee schedule as the merchant's acting operator key. */
  private void putFee(OperatorMerchant acting, String rate, String fixed) throws Exception {
    mockMvc.perform(put("/v1/merchants/" + acting.merchantId() + "/fee")
            .header("Authorization", "Bearer " + acting.operatorSecret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rate\":" + rate + ",\"fixedAmount\":" + fixed + "}"))
        .andExpect(status().isOk());
  }

  private int entryCountForMerchant(String merchantPublicId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from merchants.fee_schedule_entry e "
            + "join merchants.merchant m on m.id = e.merchant_id "
            + "where m.public_id = '" + merchantPublicId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** The operator keys named on the merchant's history, in insertion order. */
  private List<String> attributedKeysForMerchant(String merchantPublicId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select e.created_by from merchants.fee_schedule_entry e "
            + "join merchants.merchant m on m.id = e.merchant_id "
            + "where m.public_id = '" + merchantPublicId + "' order by e.id")) {
      List<String> keys = new ArrayList<>();
      while (rs.next()) {
        keys.add(rs.getObject("created_by", UUID.class).toString());
      }
      return keys;
    }
  }

  @Test
  void feePutAppendsAnAttributedEntryAndUpdatesTheCacheTogether() throws Exception {
    var merchant = createMerchantAsOperator("History A", null);
    // A merchant created after the migration carries no seed entry: its
    // history begins with its first fee PUT.
    assertEquals(0, entryCountForMerchant(merchant.merchantId()));

    putFee(merchant, "0.0099", "0.39");

    assertEquals(1, entryCountForMerchant(merchant.merchantId()));
    assertEquals(List.of(merchant.operatorKeyPublicId()),
        attributedKeysForMerchant(merchant.merchantId()));
    // The merchant's cached current schedule moved with the entry.
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId())
            .header("Authorization", "Bearer " + merchant.operatorSecret()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee.rate").value(0.0099))
        .andExpect(jsonPath("$.fee.fixedAmount").value(0.39));
  }

  @Test
  void aNoOpFeeChangeStillAppends() throws Exception {
    var merchant = createMerchantAsOperator("History B", "{\"rate\":0.015,\"fixedAmount\":0.5}");
    assertEquals(0, entryCountForMerchant(merchant.merchantId()));

    // The SAME values the merchant already carries: history is a change log,
    // not a diff log — the PUT still records who asserted the schedule.
    putFee(merchant, "0.015", "0.5");

    assertEquals(1, entryCountForMerchant(merchant.merchantId()));
    assertEquals(List.of(merchant.operatorKeyPublicId()),
        attributedKeysForMerchant(merchant.merchantId()));
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId())
            .header("Authorization", "Bearer " + merchant.operatorSecret()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee.rate").value(0.015));
  }

  @Test
  void aSecondChangeYieldsTwoEntries() throws Exception {
    var merchant = createMerchantAsOperator("History C", null);

    putFee(merchant, "0.01", "0.10");
    putFee(merchant, "0.02", "0.20");

    assertEquals(2, entryCountForMerchant(merchant.merchantId()));
    // The cache carries the SECOND rate; the fee-history listing test pins
    // the id-desc order of the entries themselves.
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId())
            .header("Authorization", "Bearer " + merchant.operatorSecret()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee.rate").value(0.02))
        .andExpect(jsonPath("$.fee.fixedAmount").value(0.20));
  }

  @Test
  void feeHistoryPaginatesWithNextCursor() throws Exception {
    var merchant = createMerchantAsOperator("History D", null);
    putFee(merchant, "0.0100", "0.10");
    putFee(merchant, "0.0200", "0.20");
    putFee(merchant, "0.0300", "0.30");
    MvcResult page1 = mockMvc
        .perform(get("/v1/merchants/" + merchant.merchantId() + "/fee-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret()).param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].entryId").exists())
        .andExpect(jsonPath("$[0].validFrom").exists())
        .andExpect(jsonPath("$[0].createdBy").value(merchant.operatorKeyPublicId()))
        .andExpect(jsonPath("$[0].createdByLabel").value("fee-probe"))
        .andExpect(header().exists("Next-Cursor"))
        .andExpect(jsonPath("$[0].rate").value(0.0300))
        .andReturn();
    String cursor = page1.getResponse().getHeader("Next-Cursor");
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId() + "/fee-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret())
            .param("limit", "2").param("after", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(header().doesNotExist("Next-Cursor"))
        .andExpect(jsonPath("$[0].rate").value(0.0100));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    var merchant = createMerchantAsOperator("History E", null);
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId() + "/fee-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret())
            .param("after", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0))
        .andExpect(header().doesNotExist("Next-Cursor"));
  }

  @Test
  void feeHistoryBoundsAndScoping() throws Exception {
    var merchant = createMerchantAsOperator("History F", null);
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId() + "/fee-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret()).param("limit", "0"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId() + "/fee-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret()).param("limit", "101"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/merchants/" + UUID.randomUUID() + "/fee-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret()))
        .andExpect(status().isNotFound());
    // Merchant keys never reach the history surface — the M8 role mismatch 403.
    String merchantKey = ApiDrivers.createMerchantAndGetKey(mockMvc,
        "Bearer " + merchant.operatorSecret(), "History Gated");
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId() + "/fee-history")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isForbidden());
  }

  @Test
  void seedAttributionRendersAsSystem() throws Exception {
    // The PRE-EXISTING seed merchant's only entry is the V16 backfill: its
    // actor predates operator identity, so the null attribution renders
    // "system". A merchant created after the migration has no seed.
    mockMvc.perform(get("/v1/merchants/" + SeedMerchant.PUBLIC_ID + "/fee-history")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].createdByLabel").value("system"))
        .andExpect(jsonPath("$[0].createdBy").value(nullValue()));
  }
}
