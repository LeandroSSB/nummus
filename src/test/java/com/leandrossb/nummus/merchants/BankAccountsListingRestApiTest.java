package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.domain.BankAccount;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The merchant's registered destinations, newest first, chained by
 *  Next-Cursor; another merchant's registrations never surface. */
@AutoConfigureMockMvc
class BankAccountsListingRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private BankAccountsService bankAccounts;

  private String merchantKey;
  private UUID merchantId;

  @BeforeEach
  void createFixtures() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Bank Accounts Listing Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    for (int i = 0; i < 4; i++) {
      ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    }
  }

  private List<String> walk(int limit) throws Exception {
    Set<String> headers = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(get("/v1/bank-accounts" + query)
              .header("Authorization", "Bearer " + merchantKey))
          .andExpect(status().isOk()).andReturn();
      ids.addAll(JsonPath.<List<String>>read(result.getResponse().getContentAsString(), "$[*].publicId"));
      String cursor = result.getResponse().getHeader("Next-Cursor");
      pages++;
      query = cursor == null ? null : "?limit=" + limit + "&after=" + cursor;
      if (cursor != null) {
        headers.add(cursor);
      }
    }
    assertEquals(pages - 1, headers.size());
    return ids;
  }

  @Test
  void cursorWalkCoversEveryAccountExactlyOnceNewestFirst() throws Exception {
    BankAccount newest = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    var ids = walk(2);
    assertEquals(5, ids.size());
    assertEquals(5, new HashSet<>(ids).size());
    assertEquals(newest.publicId().toString(), ids.get(0));
  }

  @Test
  void anotherMerchantsRegistrationsNeverSurface() throws Exception {
    // A second merchant's registration exists in the same pooled database.
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult other = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Bank Accounts Listing Other Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    UUID otherId = UUID.fromString(JsonPath.read(other.getResponse().getContentAsString(), "$.merchantId"));
    ApiDrivers.registerVerifiedBankAccount(bankAccounts, otherId);

    var ids = walk(3);
    assertEquals(4, ids.size());
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    mockMvc.perform(get("/v1/bank-accounts?after=" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    mockMvc.perform(get("/v1/bank-accounts?limit=0")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/bank-accounts?limit=101")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
  }
}
