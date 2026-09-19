package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class MerchantsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Test
  void createReturnsTheFirstKeyExactlyOnce() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rest Merchant\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.merchantId").exists())
        .andExpect(jsonPath("$.apiKey.secret").exists())
        .andExpect(jsonPath("$.apiKey.secret").isNotEmpty())
        .andReturn();
    String merchantId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.merchantId");

    mockMvc.perform(get("/v1/merchants/" + merchantId)).andExpect(status().isOk())
        .andExpect(jsonPath("$.apiKey").doesNotExist());
    mockMvc.perform(get("/v1/merchants/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void selfServeKeyLifecycleOverMe() throws Exception {
    String firstKey = createMerchant("Me Merchant");
    String auth = "Bearer " + firstKey;

    mockMvc.perform(get("/v1/me").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Me Merchant"));

    MvcResult minted = mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty())
        .andReturn();
    String secondKey = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.secret");
    String secondKeyId = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");

    mockMvc.perform(get("/v1/me/api-keys").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].prefix").exists())
        .andExpect(jsonPath("$[0].secret").doesNotExist());

    mockMvc.perform(delete("/v1/me/api-keys/" + secondKeyId).header("Authorization", auth))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + secondKey))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/v1/me/api-keys/" + secondKeyId).header("Authorization", auth))
        .andExpect(status().isNotFound());
  }

  private String createMerchant(String name) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }
}
