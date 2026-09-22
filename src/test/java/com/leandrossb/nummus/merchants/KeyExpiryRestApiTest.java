package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class KeyExpiryRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Test
  void merchantMintWithExpiresInStoresAndReturnsIt() throws Exception {
    String bearer = "Bearer " + ApiDrivers.createMerchantAndGetKey(
            mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Expiring Mint");
    MvcResult minted = mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"P1D\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty())
        .andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select expires_at > now() + interval '23 hours' "
            + "and expires_at < now() + interval '25 hours' as about_one_day "
            + "from merchants.api_key where public_id = '" + keyId + "'")) {
      Assertions.assertTrue(rs.next());
      Assertions.assertTrue(rs.getBoolean("about_one_day"));
    }
  }

  @Test
  void operatorMintWithExpiresInStoresIt() throws Exception {
    mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"probe\",\"expiresIn\":\"PT2H\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }

  @Test
  void nonPositiveExpiresInIsRejected() throws Exception {
    String bearer = "Bearer " + ApiDrivers.createMerchantAndGetKey(
            mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Bad Expiry");
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT0S\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void garbageExpiresInIsRejected() throws Exception {
    String bearer = "Bearer " + ApiDrivers.createMerchantAndGetKey(
            mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Garbage Expiry");
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"whenever\"}"))
        .andExpect(status().isBadRequest());
  }
}
