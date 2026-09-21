package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
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
class ApiKeyExpiryAuthTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private String createMerchantAndGetKey(String name) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  private String firstKeyId(String bearer) throws Exception {
    MvcResult listed = mockMvc.perform(get("/v1/me/api-keys").header("Authorization", bearer))
        .andExpect(status().isOk()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(listed.getResponse().getContentAsString(), "$[0].keyId");
  }

  private void expireKey(String keyId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update merchants.api_key set expires_at = now() - interval '1 hour' "
          + "where public_id = '" + keyId + "'");
    }
  }

  @Test
  void expiredMerchantKeyIsUnauthorized() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Expired Merchant");
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    expireKey(firstKeyId(bearer));
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void nearFutureExpiryAuthenticatesUntilItPasses() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Countdown Merchant");
    String keyId = firstKeyId(bearer);
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update merchants.api_key set expires_at = now() + interval '2 seconds' "
          + "where public_id = '" + keyId + "'");
    }
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    Thread.sleep(2500);
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredOperatorKeyIsUnauthorized() throws Exception {
    String operator = operatorKeys.create(null).secret();
    String operatorKey = "Bearer " + operator;
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", operatorKey))
        .andExpect(status().isOk());
    MvcResult listed = mockMvc.perform(get("/v1/operator/api-keys")
            .header("Authorization", operatorKey))
        .andExpect(status().isOk()).andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(
        listed.getResponse().getContentAsString(), "$[0].keyId");
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update merchants.operator_key set expires_at = now() - interval '1 hour' "
          + "where public_id = '" + keyId + "'");
    }
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", operatorKey))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void lastUsedAtIsStampedOnSuccessAndNotOn401() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Stamped Merchant");
    String keyId = firstKeyId(bearer);
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    String first;
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select last_used_at from merchants.api_key "
            + "where public_id = '" + keyId + "'")) {
      Assertions.assertTrue(rs.next());
      first = rs.getString(1);
      Assertions.assertNotNull(first);
    }
    expireKey(keyId);
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select last_used_at from merchants.api_key "
            + "where public_id = '" + keyId + "'")) {
      Assertions.assertTrue(rs.next());
      Assertions.assertEquals(first, rs.getString(1));
    }
  }

  @Test
  void operatorLastUsedAtIsStampedOnSuccess() throws Exception {
    String operatorKey = "Bearer " + operatorKeys.create(null).secret();
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", operatorKey))
        .andExpect(status().isOk());
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select last_used_at is not null as stamped "
            + "from merchants.operator_key order by id desc limit 1")) {
      Assertions.assertTrue(rs.next());
      Assertions.assertTrue(rs.getBoolean("stamped"));
    }
  }

  @Test
  void listingsExposeTheLifecycleColumns() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Listed Merchant");
    String keyId = firstKeyId(bearer);
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update merchants.api_key set expires_at = now() + interval '1 day' "
          + "where public_id = '" + keyId + "'");
    }
    mockMvc.perform(get("/v1/me/api-keys").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].expiresAt").isNotEmpty())
        .andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty());
  }
}
