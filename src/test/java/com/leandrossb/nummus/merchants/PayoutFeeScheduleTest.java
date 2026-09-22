package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.InvalidFeeScheduleException;
import com.leandrossb.nummus.merchants.application.IssuedApiKey;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The schedule's third component — the fixed payout fee — rides the same
 *  store, REST surface, and attributed history as its settlement siblings. */
@AutoConfigureMockMvc
class PayoutFeeScheduleTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MerchantsService merchants;

  @Autowired
  private OperatorKeysService operatorKeys;

  private record OperatorMerchant(String merchantId, String operatorKeyPublicId,
      String operatorSecret) {}

  /** Creates a merchant with a freshly minted operator key, mirroring the
   *  FeeHistoryRestApiTest probe: every fee PUT afterwards is attributed to
   *  that same key. */
  private OperatorMerchant createMerchantAsOperator(String name, String feeBody) throws Exception {
    IssuedApiKey operatorKey = operatorKeys.create("payout-fee-probe", null, null);
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
  private void putFee(OperatorMerchant acting, String rate, String fixed, String payoutFixed)
      throws Exception {
    mockMvc.perform(put("/v1/merchants/" + acting.merchantId() + "/fee")
            .header("Authorization", "Bearer " + acting.operatorSecret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rate\":" + rate + ",\"fixedAmount\":" + fixed
                + ",\"payoutFixedAmount\":" + payoutFixed + "}"))
        .andExpect(status().isOk());
  }

  @Test
  void scheduleCarriesThePayoutFee() throws Exception {
    // Onboarding may assert the payout component alongside its siblings.
    var onboarded = createMerchantAsOperator("Payout Fee Onboarded",
        "{\"rate\":0.01,\"fixedAmount\":0.25,\"payoutFixedAmount\":0.75}");
    var onboardedFee = merchants.findFeeSchedule(UUID.fromString(onboarded.merchantId()))
        .orElseThrow();
    assertEquals(0, onboardedFee.payoutFixedAmount().compareTo(new BigDecimal("0.75")));

    var merchant = createMerchantAsOperator("Payout Fee A", null);
    mockMvc.perform(put("/v1/merchants/" + merchant.merchantId() + "/fee")
            .header("Authorization", "Bearer " + merchant.operatorSecret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rate\":0.0099,\"fixedAmount\":0.39,\"payoutFixedAmount\":1.50}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee.rate").value(0.0099))
        .andExpect(jsonPath("$.fee.fixedAmount").value(0.39))
        .andExpect(jsonPath("$.fee.payoutFixedAmount").value(1.50));

    var stored = merchants.findFeeSchedule(UUID.fromString(merchant.merchantId())).orElseThrow();
    assertEquals(0, stored.rate().compareTo(new BigDecimal("0.0099")));
    assertEquals(0, stored.fixedAmount().compareTo(new BigDecimal("0.39")));
    assertEquals(0, stored.payoutFixedAmount().compareTo(new BigDecimal("1.50")));

    // Default merchants — the zero schedule — carry three zeros, and the REST
    // view renders the payout component as zero rather than dropping it.
    var plain = merchants.create("Payout Fee Plain", FeeSchedule.ZERO,
        UUID.fromString(merchant.operatorKeyPublicId()));
    var zero = merchants.findFeeSchedule(plain.publicId()).orElseThrow();
    assertEquals(0, zero.rate().compareTo(BigDecimal.ZERO));
    assertEquals(0, zero.fixedAmount().compareTo(BigDecimal.ZERO));
    assertEquals(0, zero.payoutFixedAmount().compareTo(BigDecimal.ZERO));
    mockMvc.perform(get("/v1/merchants/" + plain.publicId())
            .header("Authorization", "Bearer " + merchant.operatorSecret()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee.payoutFixedAmount").value(0.0));
  }

  @Test
  void negativePayoutFeeIsRejected() throws Exception {
    var merchant = createMerchantAsOperator("Payout Fee C", null);
    mockMvc.perform(put("/v1/merchants/" + merchant.merchantId() + "/fee")
            .header("Authorization", "Bearer " + merchant.operatorSecret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rate\":0.01,\"fixedAmount\":0,\"payoutFixedAmount\":-0.01}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("payoutFixedAmount must not be negative: -0.01"));
    // Construction-level pin: the record itself rejects the same values.
    assertThrows(InvalidFeeScheduleException.class,
        () -> new FeeSchedule(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-0.01")));
    assertThrows(NullPointerException.class,
        () -> new FeeSchedule(BigDecimal.ZERO, BigDecimal.ZERO, null));
  }

  @Test
  void historyRecordsThePayoutFeeAttributed() throws Exception {
    var merchant = createMerchantAsOperator("Payout Fee D", null);
    putFee(merchant, "0.0099", "0.39", "1.50");

    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      ResultSet rs = st.executeQuery("select e.payout_fixed, e.created_by "
          + "from merchants.fee_schedule_entry e "
          + "join merchants.merchant m on m.id = e.merchant_id "
          + "where m.public_id = '" + merchant.merchantId() + "' order by e.id desc limit 1");
      assertTrue(rs.next());
      assertEquals(0, rs.getBigDecimal("payout_fixed").compareTo(new BigDecimal("1.50")));
      assertEquals(merchant.operatorKeyPublicId(), rs.getObject("created_by", UUID.class).toString());
    }
    mockMvc.perform(get("/v1/merchants/" + merchant.merchantId() + "/fee-history")
            .header("Authorization", "Bearer " + merchant.operatorSecret()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].payoutFixed").value(1.50))
        .andExpect(jsonPath("$[0].createdBy").value(merchant.operatorKeyPublicId()));
  }

  @Test
  void migrationBackfillReadsZero() throws Exception {
    // The V16 backfill predates V18's column: the seed merchant's oldest —
    // and only — entry reads the zero default, both in SQL and over REST.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      ResultSet rs = st.executeQuery("select e.payout_fixed from merchants.fee_schedule_entry e "
          + "join merchants.merchant m on m.id = e.merchant_id "
          + "where m.public_id = '" + SeedMerchant.PUBLIC_ID + "' order by e.id asc limit 1");
      assertTrue(rs.next());
      assertEquals(0, rs.getBigDecimal("payout_fixed").compareTo(BigDecimal.ZERO));
    }
    mockMvc.perform(get("/v1/merchants/" + SeedMerchant.PUBLIC_ID + "/fee-history")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].payoutFixed").value(0.0));
  }
}
