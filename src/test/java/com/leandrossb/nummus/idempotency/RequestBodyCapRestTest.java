package com.leandrossb.nummus.idempotency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class RequestBodyCapRestTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @DynamicPropertySource
  static void tinyCap(DynamicPropertyRegistry registry) {
    registry.add("nummus.http.max-body-bytes", () -> "64");
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String createMerchantAndGetKey() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKeys.create(null).secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Cap Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  @Test
  void oversizedMerchantWriteIs413AndLeavesNoIdempotencyRow() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey();
    String idemKey = UUID.randomUUID().toString();
    String oversized = "{\"holderName\":\"" + "x".repeat(200) + "\"}";
    mockMvc.perform(post("/v1/accounts")
            .header("Authorization", bearer)
            .header(KEY, idemKey)
            .contentType(MediaType.APPLICATION_JSON).content(oversized))
        .andExpect(status().isPayloadTooLarge());
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(
            "select count(*) from idempotency.idempotency_keys where key = '" + idemKey + "'")) {
      Assertions.assertTrue(rs.next());
      Assertions.assertEquals(0, rs.getInt(1));
    }
  }
}
