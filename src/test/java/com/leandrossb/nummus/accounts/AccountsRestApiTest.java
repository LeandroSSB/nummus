package com.leandrossb.nummus.accounts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class AccountsRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private Ledger ledger;

  private String createAccount(String holderName) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"" + holderName + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getHeader("Location");
  }

  @Test
  void createReturns201WithLocationAndAccountBody() throws Exception {
    mockMvc.perform(post("/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"Merchant One\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.publicId").exists())
        .andExpect(jsonPath("$.holderName").value("Merchant One"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.closedAt").doesNotExist());
  }

  @Test
  void getReturnsAccountById() throws Exception {
    String location = createAccount("Merchant Two");
    String id = location.substring(location.lastIndexOf('/') + 1);
    mockMvc.perform(get(location)).andExpect(status().isOk())
        .andExpect(jsonPath("$.publicId").value(id))
        .andExpect(jsonPath("$.holderName").value("Merchant Two"));
  }

  @Test
  void lifecycleEndpointsTransitionStatus() throws Exception {
    String location = createAccount("Merchant Three");

    mockMvc.perform(post(location + "/freeze")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"));
    mockMvc.perform(post(location + "/unfreeze")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    mockMvc.perform(post(location + "/close")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"))
        .andExpect(jsonPath("$.closedAt").exists());
  }

  @Test
  void balanceAndStatementPresentNaturalSignAfterLedgerFunding() throws Exception {
    var account = accountsService.open(new OpenAccountCommand("Funded Merchant"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "rest house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    mockMvc.perform(get("/v1/accounts/{id}/balance", account.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(150.0000))
        .andExpect(jsonPath("$.currency").value("BRL"));

    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(150.0000))
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].direction").value("CREDIT"));
  }

  @Test
  void statementPaginatesViaQueryParameters() throws Exception {
    var account = accountsService.open(new OpenAccountCommand("Busy Merchant"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "rest house asset 2", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    for (int i = 1; i <= 3; i++) {
      ledger.post(new PostTransactionCommand("stmt-" + i, List.of(
          new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
          new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("1.0000")))));
    }

    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .queryParam("offset", "1").queryParam("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].memo").value("stmt-2"));
  }
}
