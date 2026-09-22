package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties = "nummus.operator.bootstrap-token=rest-bootstrap-token")
@AutoConfigureMockMvc
class OperatorAuthRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorKey() {
    return operatorKeys.create("probe", null).secret();
  }

  @Test
  void bootstrapMintsOnceOverHttp() throws Exception {
    revokeEveryActiveKey();
    mockMvc.perform(post("/v1/operator/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"rest-bootstrap-token\",\"label\":\"bootstrap\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty());
    mockMvc.perform(post("/v1/operator/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"rest-bootstrap-token\",\"label\":\"bootstrap\"}"))
        .andExpect(status().isGone());
    mockMvc.perform(post("/v1/operator/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"wrong-length-token-123\",\"label\":\"bootstrap\"}"))
        .andExpect(status().isGone()); // consumed beats invalid — hasActiveKey is checked first
  }

  @Test
  void selfServeKeyLifecycle() throws Exception {
    String auth = "Bearer " + operatorKey();
    MvcResult minted = mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"probe\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty()).andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].prefix").exists())
        .andExpect(jsonPath("$[0].secret").doesNotExist());
    mockMvc.perform(delete("/v1/operator/api-keys/" + keyId).header("Authorization", auth))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", auth))
        .andExpect(status().isOk());
    // A revoked key stops authenticating immediately: mint a fresh key, revoke
    // it over HTTP with the still-valid auth, then present the dead secret.
    var revoked = operatorKeys.create("probe", null);
    mockMvc.perform(delete("/v1/operator/api-keys/" + revoked.key().publicId())
            .header("Authorization", auth))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/v1/operator/api-keys")
            .header("Authorization", "Bearer " + revoked.secret()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void operatorKeyOnMerchantRouteIs403() throws Exception {
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + operatorKey()))
        .andExpect(status().isForbidden());
  }

  @Test
  void simulatorStaysOpen() throws Exception {
    mockMvc.perform(get("/simulator/charges/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  /** The operator-key table is shared across methods and suites; the bootstrap
   *  test starts from no ACTIVE keys so JUnit's unspecified method order cannot
   *  change the outcome. */
  private void revokeEveryActiveKey() {
    operatorKeys.list().stream()
        .filter(key -> "ACTIVE".equals(key.status()))
        .forEach(key -> operatorKeys.revoke(key.publicId()));
  }
}
