package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The intents listing as CSV: the header row is {@code IntentResponse}'s
 *  declaration order, rows run newest first, the status filter narrows to the
 *  same rows the JSON listing returns, and an out-of-range limit neither
 *  rejects nor pages — an export is the whole result. */
@AutoConfigureMockMvc
class IntentsCsvExportTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private SimulatorService simulator;

  private UUID merchantId;
  private String merchantKey;
  private UUID accountA;
  private UUID accountB;
  private PaymentIntent first;
  private PaymentIntent middle;
  private PaymentIntent newest;

  /** Three intents across two accounts; the oldest is settled so the status
   *  filter has a row to narrow away and the nullable columns a filled row. */
  @BeforeEach
  void createFixtures() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Intents Csv Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    accountA = accountsService.open(merchantId, new OpenAccountCommand("Csv Export A")).publicId();
    accountB = accountsService.open(merchantId, new OpenAccountCommand("Csv Export B")).publicId();
    first = payments.create(merchantId,
        new CreateIntentCommand(accountA, Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
    middle = payments.create(merchantId,
        new CreateIntentCommand(accountB, Money.ofBrl("20.0000"), Duration.ofMinutes(10)));
    newest = payments.create(merchantId,
        new CreateIntentCommand(accountA, Money.ofBrl("30.0000"), Duration.ofMinutes(10)));
    simulator.pay(first.chargePublicId());
    // Re-read each intent so the assertions compare the persisted instants —
    // the CSV renders what the store returns, not the pre-insert objects.
    first = payments.get(merchantId, first.publicId());
    middle = payments.get(merchantId, middle.publicId());
    newest = payments.get(merchantId, newest.publicId());
  }

  @Test
  void csvListingCarriesEveryIntentColumnNewestFirst() throws Exception {
    MvcResult result = mockMvc.perform(get("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.startsWith("attachment; filename=\"payment-intents-")))
        .andReturn();
    String[] lines = result.getResponse().getContentAsString().split("\r\n");
    assertEquals("publicId,accountId,amount,currency,status,chargeId,expiresAt,createdAt,"
        + "settledAt,fee,netAmount,refundedTotal", lines[0]);
    assertEquals(4, lines.length); // header + the three fixture intents

    // Newest first: the just-created intent is the first data row, every
    // column in the declaration order above, nullable columns empty while
    // the intent is still open.
    String[] row = lines[1].split(",", -1);
    assertEquals(12, row.length);
    assertEquals(newest.publicId().toString(), row[0]);
    assertEquals(accountA.toString(), row[1]);
    assertEquals("30.0000", row[2]);
    assertEquals("BRL", row[3]);
    assertEquals("CREATED", row[4]);
    assertEquals(newest.chargePublicId().toString(), row[5]);
    assertEquals(newest.expiresAt().toString(), row[6]);
    assertEquals(newest.createdAt().toString(), row[7]);
    assertEquals("", row[8]);
    assertEquals(middle.publicId().toString(), lines[2].split(",", -1)[0]);

    // The settled row carries its settlement facts in the nullable columns.
    String[] settledRow = lines[3].split(",", -1);
    assertEquals(first.publicId().toString(), settledRow[0]);
    assertEquals("SETTLED", settledRow[4]);
    assertEquals(first.settledAt().toString(), settledRow[8]);
    assertEquals("10.0000", settledRow[2]);
  }

  @Test
  void statusFilterNarrowsTheCsvToTheSameRowsAsTheJson() throws Exception {
    MvcResult json = mockMvc.perform(get("/v1/payment-intents").header("Authorization",
            "Bearer " + merchantKey).param("status", "CREATED"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andReturn();
    List<String> jsonIds = JsonPath.read(json.getResponse().getContentAsString(), "$[*].publicId");

    MvcResult csv = mockMvc.perform(get("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv")
            .param("status", "CREATED"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andReturn();
    String[] lines = csv.getResponse().getContentAsString().split("\r\n");
    assertEquals(3, lines.length); // header + the two CREATED intents only
    assertEquals(jsonIds.get(0), lines[1].split(",", -1)[0]);
    assertEquals(jsonIds.get(1), lines[2].split(",", -1)[0]);
  }

  @Test
  void outOfRangeLimitNeitherRejectsNorPagesOnCsvRequests() throws Exception {
    MvcResult result = mockMvc.perform(get("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv")
            .param("limit", "999"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andReturn();
    // The whole current result: still every row, no 400, no paging.
    assertEquals(4, result.getResponse().getContentAsString().split("\r\n").length);
  }
}
