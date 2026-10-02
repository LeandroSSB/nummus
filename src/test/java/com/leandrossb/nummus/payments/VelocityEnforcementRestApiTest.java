package com.leandrossb.nummus.payments;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** The daily velocity cap bounds the gross amount of intents attempted over
 *  the trailing 24 hours, across the merchant's accounts: an attempt that
 *  pushes the window strictly past its cap rejects 422 leaving no row, a
 *  window that reaches the cap exactly passes, attempts of every status count
 *  until they age out, an unset cap leaves the volume unlimited, and the
 *  static cap still speaks first. */
@AutoConfigureMockMvc
class VelocityEnforcementRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private AccountsService accountsService;

  private String operatorAuth;
  private String merchantKey;
  private UUID merchantId;
  private UUID accountId;

  @BeforeEach
  void createFixtures() throws Exception {
    operatorAuth = "Bearer " + operatorKeys.create("velocity-enforce", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Velocity Enforcement Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
    accountId = accountsService.open(merchantId, new OpenAccountCommand("Velocity Account"))
        .publicId();
  }

  private void setLimits(String intentCap, String payoutCap, String dailyVolume) throws Exception {
    String content = "{";
    if (intentCap != null) {
      content += "\"maxIntentAmount\":" + intentCap;
    }
    if (payoutCap != null) {
      content += (content.length() > 1 ? "," : "") + "\"maxPayoutAmount\":" + payoutCap;
    }
    if (dailyVolume != null) {
      content += (content.length() > 1 ? "," : "") + "\"maxDailyIntentVolume\":" + dailyVolume;
    }
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content + "}"))
        .andExpect(status().isOk());
  }

  /** A merchant-key intent attempt — the surface the velocity cap guards. */
  private ResultActions attemptIntent(String amount) throws Exception {
    return mockMvc.perform(post("/v1/payment-intents")
        .header("Authorization", "Bearer " + merchantKey)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"accountId\":\"" + accountId + "\",\"amount\":" + amount + "}"));
  }

  /** A passing attempt — returns the intent's id, the fixture the later
   *  scenarios backdate and void. */
  private UUID createIntent(String amount) throws Exception {
    MvcResult result = attemptIntent(amount)
        .andExpect(status().isCreated()).andReturn();
    return UUID.fromString(com.jayway.jsonpath.JsonPath
        .read(result.getResponse().getContentAsString(), "$.publicId"));
  }

  @Test
  void overVolumeRejects422LeavesNoRowAndAtVolumePasses() throws Exception {
    setLimits(null, null, "1000.0000");
    createIntent("600.0000");
    createIntent("390.0000");

    // 990 + 20 > 1000: the rejection names the cap it was decided against.
    attemptIntent("20.0000")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail", containsString("1000")));

    // The M22 listing proves no intent row exists for the rejection.
    mockMvc.perform(get("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));

    // Inclusive edge: 990 + 10 = 1000 reaches the cap exactly and passes.
    createIntent("10.0000");
  }

  @Test
  void windowRollsOnceAttemptsAgePast24Hours() throws Exception {
    setLimits(null, null, "1000.0000");
    var aged = createIntent("600.0000");
    createIntent("390.0000");

    // The 600 attempt leaves the window: only the 390 still counts.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.payment_intent SET created_at = now() - interval '25 hours'"
          + " WHERE public_id = '" + aged + "'");
    }

    attemptIntent("400.0000").andExpect(status().isCreated()); // 390 + 400 <= 1000
  }

  @Test
  void voidedAttemptsStillCountTowardTheWindow() throws Exception {
    setLimits(null, null, "1000.0000");
    var withdrawn = createIntent("600.0000");
    createIntent("390.0000");

    attemptIntent("20.0000")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail", containsString("990")));

    mockMvc.perform(post("/v1/payment-intents/{intentId}/void", withdrawn)
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VOIDED"));

    // The withdrawal frees no capacity: the window still carries the attempt,
    // and the same over-cap request rejects with the same numbers.
    attemptIntent("20.0000")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail", containsString("990")));
  }

  @Test
  void unsetDailyVolumeStaysUnlimited() throws Exception {
    createIntent("900.0000"); // no daily cap on record
    attemptIntent("1000000.0000").andExpect(status().isCreated());
  }

  @Test
  void staticCapSpeaksFirstAndDailyCapBoundsWhatPassesIt() throws Exception {
    setLimits("500.0000", null, "1000.0000");

    // Over the static cap the volume question never opens — and the
    // rejection leaves no row, so it never reaches the window either.
    attemptIntent("600.0000")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail", containsString("payment limit exceeded")));

    // Each attempt clears the static cap; the accumulated volume does not.
    createIntent("400.0000");
    createIntent("400.0000");
    attemptIntent("400.0000") // 800 + 400 > 1000
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail", containsString("daily intent volume exceeded")));
  }
}
