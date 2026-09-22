package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class OperatorLabelRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Test
  void operatorMintRequiresAValidLabelAndEchoesIt() throws Exception {
    String auth = ApiDrivers.operatorAuth(operatorKeys);
    mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"expiresIn\":\"P1D\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"   \",\"expiresIn\":\"P1D\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"" + "x".repeat(65) + "\"}"))
        .andExpect(status().isBadRequest());
    MvcResult minted = mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"ci-runner\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.label").value("ci-runner"))
        .andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");
    MvcResult listed = mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", auth))
        .andExpect(status().isOk())
        // The listing is newest-first (order by id desc): the just-minted key leads.
        .andExpect(jsonPath("$[0].label").value("ci-runner"))
        .andReturn();
    org.junit.jupiter.api.Assertions.assertTrue(
        listed.getResponse().getContentAsString().contains(keyId));
  }

  @Test
  void rotationCarriesTheCallingKeysLabelForward() throws Exception {
    String auth = ApiDrivers.operatorAuth(operatorKeys);
    MvcResult minted = mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"on-call\"}"))
        .andExpect(status().isCreated()).andReturn();
    String secret = com.jayway.jsonpath.JsonPath.read(
        minted.getResponse().getContentAsString(), "$.secret");
    mockMvc.perform(post("/v1/operator/api-keys/current/rotate")
            .header("Authorization", "Bearer " + secret)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.label").value("on-call"));
  }

  @Test
  void bootstrapRequiresALabel() throws Exception {
    // The bootstrap is one-time by table state; this test only pins the
    // label requirement, which fires BEFORE the one-time check — label
    // validation is the first thing bootstrap() does, so even an
    // already-bootstrapped deployment 400s on a missing label without
    // touching bootstrap state. Assert the 400 shape only.
    mockMvc.perform(post("/v1/operator/bootstrap")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void merchantKeyListingIsUnaffectedByTheNullableLabel() throws Exception {
    // The shared ApiKeyResponse gains a nullable label; merchant listings
    // still succeed. The house serializer includes nulls (no inclusion
    // config), so merchant keys render "label": null.
    String merchant = ApiDrivers.createMerchantAndGetKey(
        mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Label Merchant");
    mockMvc.perform(get("/v1/me/api-keys").header("Authorization", "Bearer " + merchant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].label").value((String) null));
  }
}
