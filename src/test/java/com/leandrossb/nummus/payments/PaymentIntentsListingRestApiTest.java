package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
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

/** The merchant's own intents, newest first, chained by Next-Cursor without
 *  overlap or gaps; filters intersect; foreign cursors resolve to nothing. */
@AutoConfigureMockMvc
class PaymentIntentsListingRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  private String merchantKey;
  private UUID merchantId;
  private UUID accountA;
  private UUID accountB;

  @BeforeEach
  void createFixtures() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Intents Listing Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    accountA = accountsService.open(merchantId, new OpenAccountCommand("Listing A")).publicId();
    accountB = accountsService.open(merchantId, new OpenAccountCommand("Listing B")).publicId();
    for (int i = 0; i < 3; i++) {
      payments.create(merchantId,
          new CreateIntentCommand(accountA, Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
    }
    payments.create(merchantId,
        new CreateIntentCommand(accountB, Money.ofBrl("20.0000"), Duration.ofMinutes(10)));
  }

  /** Walks the whole listing through Next-Cursor at the given page size,
   *  returning every public id in page order. */
  private List<String> walk(int limit) throws Exception {
    Set<String> headers = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(get("/v1/payment-intents" + query)
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
    // Every non-final page emitted a distinct cursor.
    assertEquals(pages - 1, headers.size());
    return ids;
  }

  @Test
  void cursorWalkCoversEveryIntentExactlyOnceNewestFirst() throws Exception {
    // 5 = 3 on A + 1 on B from the fixture, plus this one created just now
    // (the newest row the walk must surface first).
    var newest = payments.create(merchantId,
        new CreateIntentCommand(accountB, Money.ofBrl("20.0000"), Duration.ofMinutes(10)));
    var ids = walk(2);
    assertEquals(5, ids.size());
    assertEquals(5, new HashSet<>(ids).size()); // no overlap across pages
    assertEquals(newest.publicId().toString(), ids.get(0)); // newest first
  }

  @Test
  void statusAndAccountFiltersNarrowTheListing() throws Exception {
    mockMvc.perform(get("/v1/payment-intents?account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
    mockMvc.perform(get("/v1/payment-intents?status=SETTLED")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc.perform(get("/v1/payment-intents?status=CREATED&account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    mockMvc.perform(get("/v1/payment-intents?after=" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    mockMvc.perform(get("/v1/payment-intents?limit=0")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/payment-intents?limit=101")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
  }
}
