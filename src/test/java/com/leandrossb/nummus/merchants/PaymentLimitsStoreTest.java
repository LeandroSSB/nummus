package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.application.PaymentLimits;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Limits round-trip: cached current values on the merchant row, an
 *  attributed append-only entry per change, newest first. */
@AutoConfigureMockMvc
class PaymentLimitsStoreTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private MerchantsService merchants;

  private UUID merchantId;
  private UUID operatorKeyPublicId;

  @BeforeEach
  void createFixtures() throws Exception {
    var operatorKey = operatorKeys.create("limits-probe", null, null);
    operatorKeyPublicId = operatorKey.key().publicId();
    String operatorAuth = "Bearer " + operatorKey.secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Payment Limits Store Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath
        .read(created.getResponse().getContentAsString(), "$.merchantId"));
  }

  @Test
  void freshMerchantIsUnlimitedAndChangesAppendAttributedHistory() {
    var initial = merchants.findPaymentLimits(merchantId).orElseThrow();
    assertNull(initial.maxIntentAmount());
    assertNull(initial.maxPayoutAmount());

    merchants.updatePaymentLimits(merchantId,
        new PaymentLimits(new BigDecimal("5000.0000"), new BigDecimal("2000.0000")), operatorKeyPublicId);
    merchants.updatePaymentLimits(merchantId,
        new PaymentLimits(null, new BigDecimal("1500.0000")), operatorKeyPublicId);

    var current = merchants.findPaymentLimits(merchantId).orElseThrow();
    assertNull(current.maxIntentAmount());
    assertEquals(0, current.maxPayoutAmount().compareTo(new BigDecimal("1500.0000")));

    var history = merchants.listPaymentLimitsHistory(merchantId, null, 50);
    assertEquals(2, history.size()); // newest first
    assertNull(history.get(0).maxIntentAmount());
    assertEquals(0, history.get(0).maxPayoutAmount().compareTo(new BigDecimal("1500.0000")));
    assertEquals(operatorKeyPublicId, history.get(0).createdBy());
    assertEquals("limits-probe", history.get(0).createdByLabel());
    assertEquals(0, history.get(1).maxIntentAmount().compareTo(new BigDecimal("5000.0000")));
  }
}
