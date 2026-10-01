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
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
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

/** The merchant's own refunds, newest first, chained by Next-Cursor; filters
 *  intersect (the account filter rides the owning intent); a foreign cursor
 *  merely offsets — foreign rows never surface. */
@AutoConfigureMockMvc
class RefundsListingRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private SimulatorService simulator;

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
            .content("{\"name\":\"Refunds Listing Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    accountA = accountsService.open(merchantId, new OpenAccountCommand("Refund Listing A")).publicId();
    accountB = accountsService.open(merchantId, new OpenAccountCommand("Refund Listing B")).publicId();
    // Account A: one settled intent with three REQUESTED refunds.
    var onA = createAndSettleIntent(accountA, "200.0000");
    for (int i = 0; i < 3; i++) {
      refunds.create(merchantId, onA.publicId(),
          new CreateRefundCommand(Money.ofBrl("25.0000"), Duration.ofMinutes(10)));
    }
    // Account B: one settled intent with one refund (filter asymmetry).
    var onB = createAndSettleIntent(accountB, "100.0000");
    refunds.create(merchantId, onB.publicId(),
        new CreateRefundCommand(Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
  }

  private PaymentIntent createAndSettleIntent(UUID accountPublicId, String amount) {
    var intent = payments.create(merchantId,
        new CreateIntentCommand(accountPublicId, Money.ofBrl(amount), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    return payments.get(merchantId, intent.publicId());
  }

  private List<String> walk(int limit) throws Exception {
    Set<String> headers = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(get("/v1/refunds" + query)
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
  void cursorWalkCoversEveryRefundExactlyOnceNewestFirst() throws Exception {
    var newest = refunds.create(merchantId, createAndSettleIntent(accountA, "50.0000").publicId(),
        new CreateRefundCommand(Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
    var ids = walk(2);
    assertEquals(5, ids.size());
    assertEquals(5, new HashSet<>(ids).size());
    assertEquals(newest.publicId().toString(), ids.get(0));
  }

  @Test
  void statusAndAccountFiltersNarrowTheListing() throws Exception {
    mockMvc.perform(get("/v1/refunds?account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
    mockMvc.perform(get("/v1/refunds?account=" + accountB)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mockMvc.perform(get("/v1/refunds?status=SETTLED")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    mockMvc.perform(get("/v1/refunds?after=" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    mockMvc.perform(get("/v1/refunds?limit=0")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/refunds?limit=101")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
  }
}
