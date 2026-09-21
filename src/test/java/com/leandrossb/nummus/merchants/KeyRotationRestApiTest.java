package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class KeyRotationRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @DynamicPropertySource
  static void shortGrace(DynamicPropertyRegistry registry) {
    registry.add("nummus.api-keys.rotation-grace", () -> "PT2S");
  }

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

  @Test
  void merchantRotationReturnsNewSecretAndRetiresTheOldKeyAtGrace() throws Exception {
    String oldSecret = createMerchantAndGetKey("Rotating Merchant");
    String oldBearer = "Bearer " + oldSecret;
    MvcResult rotated = mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", oldBearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty())
        .andExpect(jsonPath("$.oldKeyExpiresAt").isNotEmpty())
        .andReturn();
    String newSecret = com.jayway.jsonpath.JsonPath.read(
        rotated.getResponse().getContentAsString(), "$.secret");
    Assertions.assertNotEquals(oldSecret, newSecret);
    // During grace the old key still works; the new key works immediately.
    mockMvc.perform(get("/v1/me").header("Authorization", oldBearer))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + newSecret))
        .andExpect(status().isOk());
    Thread.sleep(2500);
    mockMvc.perform(get("/v1/me").header("Authorization", oldBearer))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + newSecret))
        .andExpect(status().isOk());
  }

  @Test
  void rotationIsIdempotentAndReplaysTheSameSecret() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Idempotent Rotation");
    String idemKey = UUID.randomUUID().toString();
    MvcResult first = mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer).header(KEY, idemKey)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated()).andReturn();
    MvcResult replay = mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer).header(KEY, idemKey)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated()).andReturn();
    Assertions.assertEquals(first.getResponse().getContentAsString(),
        replay.getResponse().getContentAsString());
    // One rotation, not two: the listing holds exactly two keys (onboarding + replacement).
    mockMvc.perform(get("/v1/me/api-keys").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void rotationNeverExtendsANearerExpiry() throws Exception {
    // The calling key expires in 2s; rotating with a 2s grace must not push
    // the end past the already-set expiry (least()).
    String oldSecret = createMerchantAndGetKey("Nearer Expiry");
    String oldBearer = "Bearer " + oldSecret;
    MvcResult minted = mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", oldBearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT2S\"}"))
        .andExpect(status().isCreated()).andReturn();
    String expiringSecret = com.jayway.jsonpath.JsonPath.read(
        minted.getResponse().getContentAsString(), "$.secret");
    String expiringBearer = "Bearer " + expiringSecret;
    MvcResult rotated = mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", expiringBearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated()).andReturn();
    java.time.Instant mintedExpiry = java.time.Instant.parse(
        com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.expiresAt"));
    java.time.Instant oldKeyExpiry = java.time.Instant.parse(
        com.jayway.jsonpath.JsonPath.read(rotated.getResponse().getContentAsString(), "$.oldKeyExpiresAt"));
    Assertions.assertEquals(mintedExpiry, oldKeyExpiry);
    Thread.sleep(2500);
    // The rotated-away key died at its own earlier expiry, not at grace end.
    mockMvc.perform(get("/v1/me").header("Authorization", expiringBearer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredKeyCannotRotate() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Expired Rotator");
    MvcResult listed = mockMvc.perform(get("/v1/me/api-keys").header("Authorization", bearer))
        .andExpect(status().isOk()).andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(
        listed.getResponse().getContentAsString(), "$[0].keyId");
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("update merchants.api_key set expires_at = now() - interval '1 hour' "
          + "where public_id = '" + keyId + "'");
    }
    mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void operatorRotationWorksTheSameWay() throws Exception {
    String oldSecret = operatorKeys.create(null).secret();
    String oldBearer = "Bearer " + oldSecret;
    MvcResult rotated = mockMvc.perform(post("/v1/operator/api-keys/current/rotate")
            .header("Authorization", oldBearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty())
        .andExpect(jsonPath("$.oldKeyExpiresAt").isNotEmpty())
        .andReturn();
    String newSecret = com.jayway.jsonpath.JsonPath.read(
        rotated.getResponse().getContentAsString(), "$.secret");
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", oldBearer))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", "Bearer " + newSecret))
        .andExpect(status().isOk());
    Thread.sleep(2500);
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", oldBearer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rotateAcceptsExpiresInForTheNewKey() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Rotate With Lifetime");
    mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"P1D\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }

  @Test
  void nonPositiveExpiresInOnRotateIsRejected() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Rotate Bad Expiry");
    mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT0S\"}"))
        .andExpect(status().isBadRequest());
  }
}
