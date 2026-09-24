package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class BankAccountsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String merchantAuth;

  /** One merchant per test — minted lazily because an inline field initializer
   *  would run before Spring injects the drivers it needs (the operatorAuth()
   *  precedent from ConciliationRestApiTest). */
  private String merchantAuth() throws Exception {
    if (merchantAuth == null) {
      merchantAuth = "Bearer " + ApiDrivers.createMerchantAndGetKey(mockMvc,
          ApiDrivers.operatorAuth(operatorKeys), "Bank Account Merchant");
    }
    return merchantAuth;
  }

  private String body(String bankCode, String branch, String account, String taxId) {
    return "{\"bankCode\":\"" + bankCode + "\",\"branch\":\"" + branch
        + "\",\"accountNumber\":\"" + account + "\",\"holderTaxId\":\"" + taxId + "\"}";
  }

  private MvcResult register(String accountNumber) throws Exception {
    return mockMvc.perform(post("/v1/bank-accounts")
            .header("Authorization", merchantAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("123", "4567", accountNumber, "11144477735")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
        .andExpect(jsonPath("$.bankCode").value("123"))
        .andExpect(jsonPath("$.holderTaxId").value("11144477735"))
        .andReturn();
  }

  @Test
  void registrationShowsTheCodeOnceAndNeverAgain() throws Exception {
    var created = register("77101-2");
    String account = JsonPath.read(created.getResponse().getContentAsString(), "$.bankAccountId");
    String code = JsonPath.read(created.getResponse().getContentAsString(), "$.verificationCode");

    mockMvc.perform(get("/v1/bank-accounts/" + account).header("Authorization", merchantAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationCode").doesNotExist())
        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    mockMvc.perform(get("/v1/bank-accounts").header("Authorization", merchantAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.bankAccountId == '" + account + "')].status")
            .value("PENDING_VERIFICATION"));
  }

  @Test
  void verifyRedeemsTheCodeAndRejectsReplayAndWrongCode() throws Exception {
    var created = register("77201-3");
    String account = JsonPath.read(created.getResponse().getContentAsString(), "$.bankAccountId");
    String code = JsonPath.read(created.getResponse().getContentAsString(), "$.verificationCode");

    // Wrong code: 401, no mutation — the row stays PENDING_VERIFICATION.
    mockMvc.perform(post("/v1/bank-accounts/" + account + "/verify")
            .header("Authorization", merchantAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"nummus_bac_garbage\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/bank-accounts/" + account).header("Authorization", merchantAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

    mockMvc.perform(post("/v1/bank-accounts/" + account + "/verify")
            .header("Authorization", merchantAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VERIFIED"))
        .andExpect(jsonPath("$.verifiedAt").exists());

    mockMvc.perform(post("/v1/bank-accounts/" + account + "/verify")
            .header("Authorization", merchantAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"))
        .andExpect(status().isConflict());

    mockMvc.perform(post("/v1/bank-accounts/" + UUID.randomUUID() + "/verify")
            .header("Authorization", merchantAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"nummus_bac_anything\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void shapeValidationMapsTo400() throws Exception {
    mockMvc.perform(post("/v1/bank-accounts")
            .header("Authorization", merchantAuth()).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("12", "4567", "89101-2", "11144477735")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/bank-accounts")
            .header("Authorization", merchantAuth()).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("123", "4567", "89101-2", "11144477736")))
        .andExpect(status().isBadRequest()); // check digits — service-level 400
  }

  @Test
  void duplicateActiveRegistrationIs409() throws Exception {
    String body = body("123", "4567", "88101-9", "11144477735");
    mockMvc.perform(post("/v1/bank-accounts")
            .header("Authorization", merchantAuth()).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/bank-accounts")
            .header("Authorization", merchantAuth()).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void revokeIsTerminalAndScoped() throws Exception {
    var created = register("77301-4");
    String account = JsonPath.read(created.getResponse().getContentAsString(), "$.bankAccountId");

    mockMvc.perform(delete("/v1/bank-accounts/" + account).header("Authorization", merchantAuth()))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/v1/bank-accounts/" + account).header("Authorization", merchantAuth()))
        .andExpect(status().isConflict());

    // A foreign merchant's account is indistinguishable from unknown.
    String other = "Bearer " + ApiDrivers.createMerchantAndGetKey(mockMvc,
        ApiDrivers.operatorAuth(operatorKeys), "Other Bank Merchant");
    mockMvc.perform(get("/v1/bank-accounts/" + account).header("Authorization", other))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/v1/bank-accounts").header("Authorization", other))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.bankAccountId == '" + account + "')]").doesNotExist());
  }

  @Test
  void routesRequireMerchantAuth() throws Exception {
    mockMvc.perform(get("/v1/bank-accounts")).andExpect(status().isUnauthorized());
  }
}
