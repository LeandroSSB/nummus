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
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
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

/** The merchant's own payouts, newest first, chained by Next-Cursor; filters
 *  intersect; foreign cursors resolve to nothing. */
@AutoConfigureMockMvc
class PayoutsListingRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private BankAccountsService bankAccounts;

  @Autowired
  private SimulatorService simulator;

  private String merchantKey;
  private UUID merchantId;
  private UUID accountA;
  private UUID accountB;
  private UUID destinationId;

  @BeforeEach
  void createFixtures() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Payouts Listing Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    accountA = accountsService.open(merchantId, new OpenAccountCommand("Payout Listing A")).publicId();
    accountB = accountsService.open(merchantId, new OpenAccountCommand("Payout Listing B")).publicId();
    // Fund account A by settling one intent (zero fee schedule → net = gross).
    var funding = payments.create(merchantId,
        new CreateIntentCommand(accountA, Money.ofBrl("500.0000"), Duration.ofMinutes(10)));
    simulator.pay(funding.chargePublicId());
    payments.get(merchantId, funding.publicId());
    destinationId = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId).publicId();
    for (int i = 0; i < 3; i++) {
      payouts.create(merchantId, new CreatePayoutCommand(accountA, Money.ofBrl("50.0000"),
          destinationId, Duration.ofMinutes(10)));
    }
    payouts.create(merchantId, new CreatePayoutCommand(accountB, Money.ofBrl("20.0000"),
        destinationId, Duration.ofMinutes(10)));
  }

  private List<String> walk(int limit) throws Exception {
    Set<String> headers = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(get("/v1/payouts" + query)
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
  void cursorWalkCoversEveryPayoutExactlyOnceNewestFirst() throws Exception {
    var newest = payouts.create(merchantId, new CreatePayoutCommand(accountB,
        Money.ofBrl("20.0000"), destinationId, Duration.ofMinutes(10)));
    var ids = walk(2);
    assertEquals(5, ids.size());
    assertEquals(5, new HashSet<>(ids).size());
    assertEquals(newest.publicId().toString(), ids.get(0));
  }

  @Test
  void statusAndAccountFiltersNarrowTheListing() throws Exception {
    mockMvc.perform(get("/v1/payouts?account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
    mockMvc.perform(get("/v1/payouts?status=SETTLED")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc.perform(get("/v1/payouts?status=REQUESTED&account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    mockMvc.perform(get("/v1/payouts?after=" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    mockMvc.perform(get("/v1/payouts?limit=0")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/payouts?limit=101")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
  }
}
