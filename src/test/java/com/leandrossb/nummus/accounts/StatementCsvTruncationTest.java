package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.TransfersService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreateTransferCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The statement export under a row cap: the composition section still opens
 *  the file whole, the postings table carries only the capped newest rows,
 *  and the closing marker line says the export is not the account's full
 *  story — a truncated accounting read must never pass silently. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "nummus.export.max-rows=3")
class StatementCsvTruncationTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private TransfersService transfers;

  @Autowired
  private SimulatorService simulator;

  private UUID merchantId;
  private String merchantKey;
  private UUID fundedAccount;
  private UUID destinationAccount;
  private List<UUID> transferIds;

  /** A funded account and a sibling destination: four intra-merchant transfers
   *  book exactly four CREDIT lines on the destination — one more than the
   *  class's export cap, so the statement CSV must truncate and say so. */
  @BeforeEach
  void createFourLineStatementFixture() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Statement Truncation Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
    fundedAccount = accountsService
        .open(merchantId, new OpenAccountCommand("Statement Truncation Source")).publicId();
    destinationAccount = accountsService
        .open(merchantId, new OpenAccountCommand("Statement Truncation Destination")).publicId();
    var funding = payments.create(merchantId, new CreateIntentCommand(fundedAccount,
        Money.ofBrl("100.0000"), Duration.ofMinutes(10)));
    simulator.pay(funding.chargePublicId());
    payments.get(merchantId, funding.publicId());
    transferIds = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      transferIds.add(transfers.create(merchantId,
          new CreateTransferCommand(fundedAccount, destinationAccount, Money.ofBrl("1.0000")))
          .publicId());
    }
  }

  @Test
  void statementExportTruncatesAtTheConfiguredRowCapWithAMarker() throws Exception {
    MvcResult result = mockMvc
        .perform(get("/v1/accounts/{id}/statement", destinationAccount)
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andReturn();
    String[] lines = result.getResponse().getContentAsString().split("\r\n");
    // Composition header, values, blank separator, table header, the capped
    // three rows, the truncation marker.
    assertEquals(8, lines.length);

    // The composition section survives the cap whole: all four credits are
    // composed into the balance figure even though only three rows render.
    assertEquals("balance,pendingIncoming,reservedOutgoing", lines[0]);
    assertEquals("4.0000,0,0", lines[1]);
    assertEquals("", lines[2]);

    // The postings table: the newest three of the four transfers, newest
    // first — the oldest one is the row the cap drops.
    assertEquals("bookedAt,transactionPublicId,memo,direction,amount,currency", lines[3]);
    for (int i = 0; i < 3; i++) {
      String[] row = lines[4 + i].split(",", -1);
      assertEquals(6, row.length);
      assertEquals("transfer " + transferIds.get(3 - i), row[2]);
      assertEquals("CREDIT", row[3]);
      assertEquals("1.0000", row[4]);
      assertEquals("BRL", row[5]);
    }

    // The marker closes the file: the reader knows the export is incomplete.
    assertEquals("# truncated: true", lines[7]);
  }
}
