package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private BankAccountsService bankAccounts;

  private String merchantKey;
  private UUID merchantId;

  /** Fresh merchant per test: every account this class touches belongs to it.
   *  Creation is operator-gated — one operator key per fixture mint. */
  @BeforeEach
  void createMerchantFixture() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Accounts Fixture Merchant\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
  }

  private String createAccount(String holderName) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"" + holderName + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getHeader("Location");
  }

  /** A payment account funded by a direct journal posting — the funding shape
   *  the balance/statement probes above use; payout requests derive from it. */
  private PaymentAccount fundedAccount(String amount) {
    var account = accountsService.open(merchantId, new OpenAccountCommand("Payout Guard Merchant"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "payout guard house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new PostTransactionCommand("payout guard funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl(amount)),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl(amount)))));
    return account;
  }

  /** A REQUESTED payout on the fixture merchant's funded account, bound to a
   *  freshly registered verified destination. */
  private Payout requestedPayout(PaymentAccount account, String amount) {
    var bankAccount = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    return payouts.create(merchantId, new CreatePayoutCommand(
        account.publicId(), Money.ofBrl(amount), bankAccount.publicId(), null));
  }

  @Test
  void createReturns201WithLocationAndAccountBody() throws Exception {
    mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
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
    mockMvc.perform(get(location).header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publicId").value(id))
        .andExpect(jsonPath("$.holderName").value("Merchant Two"));
  }

  @Test
  void lifecycleEndpointsTransitionStatus() throws Exception {
    String location = createAccount("Merchant Three");

    mockMvc.perform(post(location + "/freeze")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"));
    mockMvc.perform(post(location + "/unfreeze")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    mockMvc.perform(post(location + "/close")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"))
        .andExpect(jsonPath("$.closedAt").exists());
  }

  @Test
  void balanceAndStatementPresentNaturalSignAfterLedgerFunding() throws Exception {
    var account = accountsService.open(merchantId, new OpenAccountCommand("Funded Merchant"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "rest house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    mockMvc.perform(get("/v1/accounts/{id}/balance", account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(150.0000))
        .andExpect(jsonPath("$.currency").value("BRL"));

    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(150.0000))
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].direction").value("CREDIT"))
        .andExpect(jsonPath("$.lines[0].currency").value("BRL"));
  }

  @Test
  void statementPaginatesViaQueryParameters() throws Exception {
    var account = accountsService.open(merchantId, new OpenAccountCommand("Busy Merchant"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "rest house asset 2", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    for (int i = 1; i <= 3; i++) {
      ledger.post(new PostTransactionCommand("stmt-" + i, List.of(
          new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
          new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("1.0000")))));
    }

    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .header("Authorization", "Bearer " + merchantKey)
            .queryParam("offset", "1").queryParam("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].memo").value("stmt-2"));
  }

  @Test
  void unknownAccountIdReturns404Problem() throws Exception {
    mockMvc.perform(get("/v1/accounts/{id}", UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").exists())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void blankHolderNameReturns400() throws Exception {
    mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void malformedUuidReturns400() throws Exception {
    mockMvc.perform(get("/v1/accounts/not-a-uuid")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
  }

  @Test
  void lifecycleConflictOnClosedAccountReturns409() throws Exception {
    String location = createAccount("Conflict Merchant");
    mockMvc.perform(post(location + "/close")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk());
    mockMvc.perform(post(location + "/freeze")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void freezeIsRejectedWhileAPayoutIsRequested() throws Exception {
    var account = fundedAccount("50.0000");
    var payout = requestedPayout(account, "20.0000");
    String location = "/v1/accounts/" + account.publicId();

    // Freezing would strand the payout: its return and fee legs post against
    // the merchant's ledger account, which the ledger rejects once non-ACTIVE.
    mockMvc.perform(post(location + "/freeze")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());

    // Nothing moved: the payment account and its backing ledger account stay
    // ACTIVE, and the payout is still REQUESTED and readable.
    mockMvc.perform(get(location).header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    assertEquals(com.leandrossb.nummus.ledger.domain.AccountStatus.ACTIVE,
        ledger.getAccount(account.ledgerAccountPublicId()).status());
    assertEquals(PayoutStatus.REQUESTED, payouts.get(merchantId, payout.publicId()).status());
  }

  @Test
  void closeIsRejectedWhileAPayoutIsRequested() throws Exception {
    var account = fundedAccount("50.0000");
    var payout = requestedPayout(account, "20.0000");

    // Close is permanent — a REQUESTED payout on a CLOSED account could never
    // post its terminal legs.
    mockMvc.perform(post("/v1/accounts/" + account.publicId() + "/close")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());

    mockMvc.perform(get("/v1/accounts/" + account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    assertEquals(com.leandrossb.nummus.ledger.domain.AccountStatus.ACTIVE,
        ledger.getAccount(account.ledgerAccountPublicId()).status());
    assertEquals(PayoutStatus.REQUESTED, payouts.get(merchantId, payout.publicId()).status());
  }

  @Test
  void freezeSucceedsOnceThePayoutTerminates() throws Exception {
    var account = fundedAccount("50.0000");
    var payout = requestedPayout(account, "20.0000");
    String location = "/v1/accounts/" + account.publicId();

    mockMvc.perform(post(location + "/freeze")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isConflict());

    // Terminate the payout the lazy way — backdate its expiry, then read it.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.payout SET expires_at = now() - interval '1 second' "
          + "WHERE public_id = '" + payout.publicId() + "'");
    }
    assertEquals(PayoutStatus.EXPIRED, payouts.get(merchantId, payout.publicId()).status());

    // With no payout in flight the freeze goes through...
    mockMvc.perform(post(location + "/freeze")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"));
    // ...and unfreeze carries no in-flight guard: the account returns to ACTIVE.
    mockMvc.perform(post(location + "/unfreeze")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void paginationBeyondBoundsReturns400() throws Exception {
    var account = accountsService.open(merchantId, new OpenAccountCommand("Paged Merchant"));
    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .header("Authorization", "Bearer " + merchantKey)
            .queryParam("limit", "501"))
        .andExpect(status().isBadRequest());
  }
}
