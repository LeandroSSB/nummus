package com.leandrossb.nummus.testutils;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The REST test drivers the API suites share: minting operator auth,
 *  creating a merchant over HTTP, and the loopback webhook URL. Each helper
 *  is the exact behavior of the private copies it replaces — a fresh
 *  operator key per call, the first API key's secret read from the creation
 *  response, unique loopback paths per fixture. */
public final class ApiDrivers {

  private ApiDrivers() {}

  /** A fresh operator bearer value: mints a new operator key per call, so
   *  each caller gets its own key (and its own rate-limit bucket). */
  public static String operatorAuth(OperatorKeysService operatorKeys) {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  /** POSTs the merchant as the operator and returns the first API key's
   *  secret — shown exactly once, on the creation response. */
  public static String createMerchantAndGetKey(MockMvc mockMvc, String operatorAuth, String name)
      throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  /** Port 9 (discard): a loopback URL the URL policy accepts that nothing
   *  ever contacts. Unique path per registration keeps fixtures disjoint. */
  public static String loopbackUrl(String tag) {
    return "http://127.0.0.1:9/" + tag + "-" + UUID.randomUUID();
  }
}
