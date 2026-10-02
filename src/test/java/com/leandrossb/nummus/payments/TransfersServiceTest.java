package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostedPosting;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.TransferEventTypes;
import com.leandrossb.nummus.payments.application.TransfersService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreateTransferCommand;
import com.leandrossb.nummus.payments.domain.InsufficientFundsException;
import com.leandrossb.nummus.payments.domain.Transfer;
import com.leandrossb.nummus.payments.domain.UnknownTransferException;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The internal transfer over the real context: one balanced journal entry —
 * debit from, credit to — moves booked funds between two of the merchant's
 * accounts, committed atomically with the transfer row under the from-account's
 * ledger row lock. Every collaborator is the real bean, outbox included: the
 * completion event rides the same commit as the row and its journal entry.
 * Service-level on purpose — the HTTP surface arrives with Task 2.
 */
@AutoConfigureMockMvc
class TransfersServiceTest extends IntegrationTestBase {

  /** Fixtures this class settles — backdated in {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private TransfersService transfers;

  @Autowired
  private SimulatorService simulator;

  @Autowired
  private Ledger ledger;

  private UUID merchantId;
  private PaymentAccount from;
  private PaymentAccount to;

  /** The merchant fixture the sibling suites mint: a fresh merchant per test
   *  over HTTP, operator-authenticated. The default schedule prices nothing,
   *  so a settle lands the full gross as available balance. */
  @BeforeEach
  void createFixtures() throws Exception {
    merchantId = createMerchant("Internal Transfers Merchant");
    from = accountsService.open(merchantId, new OpenAccountCommand("Transfers From Account"));
    to = accountsService.open(merchantId, new OpenAccountCommand("Transfers To Account"));
  }

  private UUID createMerchant(String name) throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("transfers-probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return UUID.fromString(
        JsonPath.read(created.getResponse().getContentAsString(), "$.merchantId"));
  }

  /** Funds the account by one settled intent — the established recipe: create
   *  the intent, pay the charge on the simulator, first poll settles. */
  private void fund(UUID accountPublicId, String amount) {
    var intent = payments.create(merchantId,
        new CreateIntentCommand(accountPublicId, Money.ofBrl(amount), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(merchantId, intent.publicId());
  }

  private Transfer transferOf(UUID fromAccountPublicId, UUID toAccountPublicId, String amount) {
    return transfers.create(merchantId, new CreateTransferCommand(
        fromAccountPublicId, toAccountPublicId, Money.ofBrl(amount)));
  }

  @Test
  void createMovesBookedBalancesAndPostsOneBalancedEntry() throws Exception {
    fund(from.publicId(), "100.0000");
    assertEquals(0, accountsService.balance(merchantId, from.publicId())
        .compareTo(Money.ofBrl("100.0000")));
    assertEquals(0, accountsService.balance(merchantId, to.publicId())
        .compareTo(Money.ofBrl("0.0000")));

    var transfer = transferOf(from.publicId(), to.publicId(), "30.0000");

    // Booked balances move by exactly the amount, in opposite directions.
    assertEquals(0, accountsService.balance(merchantId, from.publicId())
        .compareTo(Money.ofBrl("70.0000")));
    assertEquals(0, accountsService.balance(merchantId, to.publicId())
        .compareTo(Money.ofBrl("30.0000")));
    // The journal link IS the record: one balanced entry, debit from, credit to.
    var entry = ledger.getTransaction(transfer.journalTransactionPublicId());
    assertEquals("transfer " + transfer.publicId(), entry.memo());
    assertEquals(2, entry.postings().size(), entry.postings().toString());
    assertLeg(entry.postings(), from.ledgerAccountPublicId(), Direction.DEBIT, "30.0000");
    assertLeg(entry.postings(), to.ledgerAccountPublicId(), Direction.CREDIT, "30.0000");
    // The completion event rides the same commit, carrying the journal link.
    try (var c = adminConnection(); var st = c.createStatement()) {
      String payload = eventPayload(st, TransferEventTypes.COMPLETED, transfer.publicId());
      assertTrue(payload.contains(
          "\"journalTransactionId\":\"" + transfer.journalTransactionPublicId() + "\""), payload);
    }
  }

  @Test
  void insufficientFromAccountRejectsWithoutATrace() {
    fund(from.publicId(), "20.0000");

    assertThrows(InsufficientFundsException.class,
        () -> transferOf(from.publicId(), to.publicId(), "30.0000"));

    // No transfer row, and neither balance moved.
    assertTrue(transfers.list(merchantId, null, 100).isEmpty());
    assertEquals(0, accountsService.balance(merchantId, from.publicId())
        .compareTo(Money.ofBrl("20.0000")));
    assertEquals(0, accountsService.balance(merchantId, to.publicId())
        .compareTo(Money.ofBrl("0.0000")));
  }

  @Test
  void frozenFromAccountRejects() {
    fund(from.publicId(), "50.0000");
    accountsService.freeze(merchantId, from.publicId());

    assertThrows(PaymentAccountNotActiveException.class,
        () -> transferOf(from.publicId(), to.publicId(), "10.0000"));

    // The freeze guard fires before any money moves: no row, both balances
    // unchanged.
    assertTrue(transfers.list(merchantId, null, 100).isEmpty());
    assertEquals(0, accountsService.balance(merchantId, from.publicId())
        .compareTo(Money.ofBrl("50.0000")));
    assertEquals(0, accountsService.balance(merchantId, to.publicId())
        .compareTo(Money.ofBrl("0.0000")));
  }

  @Test
  void sameAccountAsBothEndsRejects() {
    assertThrows(IllegalArgumentException.class,
        () -> transferOf(from.publicId(), from.publicId(), "10.0000"));
    assertTrue(transfers.list(merchantId, null, 100).isEmpty());
  }

  @Test
  void foreignTargetAccountIsUnknown() throws Exception {
    var otherMerchant = createMerchant("Internal Transfers Other");
    var foreignAccount = accountsService.open(otherMerchant,
        new OpenAccountCommand("Foreign Account"));

    assertThrows(UnknownPaymentAccountException.class,
        () -> transferOf(from.publicId(), foreignAccount.publicId(), "10.0000"));
    assertThrows(UnknownPaymentAccountException.class,
        () -> transferOf(from.publicId(), UUID.randomUUID(), "10.0000"));
  }

  @Test
  void listIsNewestFirstAndGetMasksForeignTransfers() throws Exception {
    fund(from.publicId(), "100.0000");
    var first = transferOf(from.publicId(), to.publicId(), "10.0000");
    var second = transferOf(from.publicId(), to.publicId(), "10.0000");
    var third = transferOf(from.publicId(), to.publicId(), "10.0000");

    assertEquals(List.of(third.publicId(), second.publicId(), first.publicId()),
        transfers.list(merchantId, null, 10).stream().map(Transfer::publicId).toList());
    // The keyset cursor picks up strictly below the named transfer.
    assertEquals(List.of(second.publicId(), first.publicId()),
        transfers.list(merchantId, third.publicId(), 10).stream()
            .map(Transfer::publicId).toList());
    assertEquals(first.publicId(), transfers.get(merchantId, first.publicId()).publicId());

    // Ownership masks: another merchant's transfer is indistinguishable from
    // an unknown one — and so is an unknown id.
    var otherMerchant = createMerchant("Internal Transfers Other");
    assertThrows(UnknownTransferException.class,
        () -> transfers.get(otherMerchant, first.publicId()));
    assertThrows(UnknownTransferException.class,
        () -> transfers.get(merchantId, UUID.randomUUID()));
  }

  private static void assertLeg(List<PostedPosting> postings, UUID accountPublicId,
      Direction direction, String amount) {
    var matches = postings.stream()
        .filter(p -> accountPublicId.equals(p.accountPublicId()) && p.direction() == direction)
        .toList();
    assertEquals(1, matches.size(), postings.toString());
    assertEquals(0, matches.get(0).amount().compareTo(Money.ofBrl(amount)), postings.toString());
  }

  /** This transfer's event of the given type, scoped by public id — the shared
   *  container legitimately holds other classes' events. */
  private static String eventPayload(Statement st, String type, UUID transferPublicId)
      throws SQLException {
    try (ResultSet rs = st.executeQuery(
        "select payload from webhooks.webhook_event where type = '" + type
            + "' and payload like '%\"" + transferPublicId + "\"%'")) {
      assertTrue(rs.next());
      return rs.getString(1);
    }
  }

  /**
   * The container is shared across classes and the conciliation suites assert
   * over now-relative windows. Push this class's settlements and network rows
   * two hours back — the same DB-side rewrite the sibling suites use — so
   * they never fall inside another test's window.
   */
  @AfterAll
  static void moveFixturesOutOfNowWindows() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      if (!settledIntents.isEmpty()) {
        st.executeUpdate("UPDATE payments.payment_intent SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledIntents) + ")");
      }
      if (!networkCharges.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(networkCharges) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
