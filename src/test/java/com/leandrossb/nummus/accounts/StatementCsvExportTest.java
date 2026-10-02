package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The statement as a spreadsheet: {@code Accept: text/csv} answers the
 *  composition first (header row, values row, blank separator line), then the
 *  postings table — RFC 4180, CRLF, attachment disposition. A request without
 *  the header still gets the JSON shape. */
@AutoConfigureMockMvc
class StatementCsvExportTest extends IntegrationTestBase {

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
  private PaymentAccount account;
  private PaymentIntent settled;

  /** A zero-fee merchant whose account is funded by one settled intent — the
   *  statement therefore carries exactly one CREDIT line and balance = net. */
  @BeforeEach
  void createFundedAccountFixture() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Statement Csv Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
    account = accountsService.open(merchantId, new OpenAccountCommand("Statement Csv Holder"));
    var intent = payments.create(merchantId, new CreateIntentCommand(account.publicId(),
        Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    // Settlement lands lazily on the read; the settled fact carries the journal
    // link and instant the CSV row must reproduce.
    settled = payments.get(merchantId, intent.publicId());
  }

  @Test
  void csvStatementCarriesCompositionThenLinesTable() throws Exception {
    MvcResult result = mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andExpect(header().string("Content-Disposition",
            org.hamcrest.Matchers.startsWith("attachment; filename=\"statement-")))
        .andReturn();
    String[] lines = result.getResponse().getContentAsString().split("\r\n");
    // Header, values row, blank separator, table header, exactly one line row.
    assertEquals(5, lines.length);

    // Section one: the composition figures the balance read carries.
    assertEquals("balance,pendingIncoming,reservedOutgoing", lines[0]);
    assertEquals("10.0000,0,0", lines[1]);
    assertEquals("", lines[2]);

    // Section two: the postings table, all six columns of the one settlement.
    assertEquals("bookedAt,transactionId,memo,direction,amount,currency", lines[3]);
    String[] row = lines[4].split(",", -1);
    assertEquals(6, row.length);
    assertEquals(settled.journalTransactionPublicId().toString(), row[1]);
    assertEquals("settlement " + settled.publicId(), row[2]);
    assertEquals("CREDIT", row[3]);
    assertEquals("10.0000", row[4]);
    assertEquals("BRL", row[5]);
    // bookedAt renders as an ISO instant inside the settlement's window —
    // booked_at and settled_at are stamped by the same commit.
    Instant bookedAt = Instant.parse(row[0]);
    assertTrue(!bookedAt.isBefore(settled.settledAt().minusSeconds(5))
        && !bookedAt.isAfter(settled.settledAt().plusSeconds(5)));
  }

  @Test
  void jsonRemainsTheDefaultRepresentation() throws Exception {
    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.balance").value(10.0000))
        .andExpect(jsonPath("$.pendingIncoming").value(0.0000))
        .andExpect(jsonPath("$.reservedOutgoing").value(0.0000))
        .andExpect(jsonPath("$.lines.length()").value(1));
  }
}
