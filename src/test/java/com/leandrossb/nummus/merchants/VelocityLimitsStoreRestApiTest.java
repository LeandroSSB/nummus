package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/** The velocity knob rides the M23 limits surfaces unchanged: cached current
 *  value on the merchant row, attributed append-only history beside it, one
 *  operator-governed REST resource — a third column, additive everywhere,
 *  null meaning unlimited. */
@AutoConfigureMockMvc
class VelocityLimitsStoreRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private MerchantsService merchants;

  private String operatorAuth;
  private UUID merchantId;
  private UUID operatorKeyPublicId;

  @BeforeEach
  void createFixtures() throws Exception {
    var operatorKey = operatorKeys.create("velocity-probe", null, null);
    operatorKeyPublicId = operatorKey.key().publicId();
    operatorAuth = "Bearer " + operatorKey.secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Velocity Limits Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath
        .read(created.getResponse().getContentAsString(), "$.merchantId"));
  }

  @Test
  void dailyVolumeRoundTripsAndClearsLikeTheStaticCaps() {
    merchants.updatePaymentLimits(merchantId,
        new PaymentLimits(new BigDecimal("5000"), null, new BigDecimal("1000")),
        operatorKeyPublicId);

    var current = merchants.findPaymentLimits(merchantId).orElseThrow();
    assertEquals(0, current.maxIntentAmount().compareTo(new BigDecimal("5000")));
    assertNull(current.maxPayoutAmount());
    assertEquals(0, current.maxDailyIntentVolume().compareTo(new BigDecimal("1000")));

    var history = merchants.listPaymentLimitsHistory(merchantId, null, 50);
    assertEquals(1, history.size());
    assertEquals(0, history.get(0).maxDailyIntentVolume().compareTo(new BigDecimal("1000")));
    assertEquals(operatorKeyPublicId, history.get(0).createdBy());
    assertEquals("velocity-probe", history.get(0).createdByLabel());

    // A replacement carrying only the static caps clears the daily knob.
    merchants.updatePaymentLimits(merchantId,
        new PaymentLimits(new BigDecimal("6000.0000"), new BigDecimal("2000.0000"), null),
        operatorKeyPublicId);

    var cleared = merchants.findPaymentLimits(merchantId).orElseThrow();
    assertEquals(0, cleared.maxIntentAmount().compareTo(new BigDecimal("6000.0000")));
    assertNull(cleared.maxDailyIntentVolume());

    history = merchants.listPaymentLimitsHistory(merchantId, null, 50);
    assertEquals(2, history.size()); // newest first
    assertNull(history.get(0).maxDailyIntentVolume());
    assertEquals(0, history.get(0).maxPayoutAmount().compareTo(new BigDecimal("2000.0000")));
    assertEquals(0, history.get(1).maxDailyIntentVolume().compareTo(new BigDecimal("1000")));
  }

  @Test
  void putStoresDailyVolumeAndGetAndHistoryReturnIt() throws Exception {
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxIntentAmount\":5000.0000,\"maxDailyIntentVolume\":1000.0000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxIntentAmount").value(5000.0000))
        .andExpect(jsonPath("$.maxPayoutAmount").doesNotExist())
        .andExpect(jsonPath("$.maxDailyIntentVolume").value(1000.0000));

    mockMvc.perform(get("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxIntentAmount").value(5000.0000))
        .andExpect(jsonPath("$.maxDailyIntentVolume").value(1000.0000));

    mockMvc.perform(get("/v1/merchants/{id}/limits-history", merchantId)
            .header("Authorization", operatorAuth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].maxIntentAmount").value(5000.0000))
        .andExpect(jsonPath("$[0].maxDailyIntentVolume").value(1000.0000))
        .andExpect(jsonPath("$[0].createdByLabel").value("velocity-probe"));
  }

  @Test
  void invalidDailyVolumeAndUnknownMerchantsAreRejected() throws Exception {
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxDailyIntentVolume\":-5}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(put("/v1/merchants/{id}/limits", UUID.randomUUID())
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxDailyIntentVolume\":5}"))
        .andExpect(status().isNotFound());
  }
}
