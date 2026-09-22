package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostedPosting;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.FeeRevenueAccount;
import com.leandrossb.nummus.payments.application.PaymentClearingAccount;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutReservedAccount;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import com.leandrossb.nummus.payments.domain.UnknownPayoutException;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The lazy payout lifecycle: the first {@code get} after the network moves the
 * transfer settles or returns the reservation, posts the terminal journal legs,
 * and publishes the lifecycle event. Service-level on purpose — the HTTP
 * surface arrives with Task 6.
 */
class PayoutLifecycleTest extends IntegrationTestBase {

  /** Fixtures this class settles/charges/transfers — backdated in
   *  {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();
  private static final List<UUID> payoutIds = new ArrayList<>();
  private static final List<UUID> networkTransfers = new ArrayList<>();

  @Autowired
  private PayoutsService payouts;
  @Autowired
  private PaymentsService payments;
  @Autowired
  private AccountsService accountsService;
  @Autowired
  private SimulatorService simulator;
  @Autowired
  private Ledger ledger;
  @Autowired
  private MerchantsService merchants;
  @Autowired
  private OperatorKeysService operatorKeys;

  /** A payment account funded by one settled intent — the settle recipe the
   *  payout request suite uses (create intent, pay the charge, first poll settles). */
  private PaymentAccount fundedAccount(UUID merchantPublicId, String amount) {
    var account = accountsService.open(merchantPublicId, new OpenAccountCommand("Payout Lifecycle Merchant"));
    var intent = payments.create(merchantPublicId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(merchantPublicId, intent.publicId());
    return account;
  }

  /** A merchant whose schedule prices every payout at a fixed fee. */
  private UUID payoutFeeMerchant(String payoutFixed) {
    UUID actingKey = operatorKeys.create("payout-lifecycle-probe", null, null).key().publicId();
    return merchants.create("Payout Lifecycle Fee " + UUID.randomUUID(),
        new FeeSchedule(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(payoutFixed)), actingKey)
        .publicId();
  }

  private Payout payoutOf(UUID merchantPublicId, UUID accountPublicId, String amount, String bankKey) {
    var payout = payouts.create(merchantPublicId, new CreatePayoutCommand(
        accountPublicId, Money.ofBrl(amount), bankKey, null));
    payoutIds.add(payout.publicId());
    networkTransfers.add(payout.transferPublicId());
    return payout;
  }

  @Test
  void executedTransferSettlesThePayout() throws Exception {
    var account = fundedAccount(SeedMerchant.PUBLIC_ID, "100.0000");
    var clearingBefore = ledger.balance(PaymentClearingAccount.PUBLIC_ID);
    var payout = payoutOf(SeedMerchant.PUBLIC_ID, account.publicId(), "30.0000", "bank.execute-01");

    simulator.payTransfer(payout.transferPublicId());
    var settled = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());

    assertEquals(PayoutStatus.SETTLED, settled.status());
    assertEquals(payout.publicId(), settled.publicId());
    // The gross already left at request; a zero-fee execution moves reserve to
    // clearing and never touches the merchant's balance.
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("70.0000")));
    // Clearing is a debit-normal asset: the execution credits it, so its raw
    // balance drops by exactly the payout amount (delta — the account is pooled).
    assertEquals(0, clearingBefore.subtract(ledger.balance(PaymentClearingAccount.PUBLIC_ID))
        .compareTo(Money.ofBrl("30.0000")));
    // The row carries the execution link, and the link posts the two legs
    // verbatim: debit the reserve, credit clearing. Zero fee posts no fee legs.
    assertNotNull(settled.executeTransactionPublicId());
    assertNull(settled.returnTransactionPublicId());
    var execution = ledger.getTransaction(settled.executeTransactionPublicId());
    assertEquals("payout " + payout.publicId() + " execute", execution.memo());
    assertEquals(2, execution.postings().size(), execution.postings().toString());
    assertLeg(execution.postings(), PayoutReservedAccount.PUBLIC_ID, Direction.DEBIT, "30.0000");
    assertLeg(execution.postings(), PaymentClearingAccount.PUBLIC_ID, Direction.CREDIT, "30.0000");
    // A settled zero-fee payout carries the 0.00 fact, never null.
    assertNotNull(settled.feeAmount());
    assertEquals(0, settled.feeAmount().compareTo(Money.ofBrl("0.00")));
    try (var c = adminConnection(); var st = c.createStatement()) {
      String payload = eventPayload(st, "payout.settled", payout.publicId());
      assertTrue(payload.contains("\"transferId\":\"" + payout.transferPublicId() + "\""), payload);
      assertTrue(payload.contains("\"destinationBankKey\":\"bank.execute-01\""), payload);
      assertTrue(payload.contains("\"fee\":\"0.00\""), payload);
      assertTrue(payload.contains("\"netAmount\":\"30.00\""), payload);
    }
  }

  @Test
  void settlementWithFeeChargesTheMerchant() throws Exception {
    var merchant = payoutFeeMerchant("2.00");
    var account = fundedAccount(merchant, "100.0000");
    var feeRevenueBefore = ledger.balance(FeeRevenueAccount.PUBLIC_ID);
    var payout = payoutOf(merchant, account.publicId(), "30.0000", "bank.fee-01");

    simulator.payTransfer(payout.transferPublicId());
    var settled = payouts.get(merchant, payout.publicId());

    assertEquals(PayoutStatus.SETTLED, settled.status());
    // The fee leaves the merchant's balance on top of the reserved gross.
    assertEquals(0, accountsService.balance(merchant, account.publicId())
        .compareTo(Money.ofBrl("68.0000")));
    assertEquals(0, settled.feeAmount().compareTo(Money.ofBrl("2.0000")));
    // Revenue is credit-normal: its raw balance drops by the fee.
    assertEquals(0, feeRevenueBefore.subtract(ledger.balance(FeeRevenueAccount.PUBLIC_ID))
        .compareTo(Money.ofBrl("2.0000")));
    var execution = ledger.getTransaction(settled.executeTransactionPublicId());
    assertEquals(4, execution.postings().size(), execution.postings().toString());
    assertLeg(execution.postings(), PayoutReservedAccount.PUBLIC_ID, Direction.DEBIT, "30.0000");
    assertLeg(execution.postings(), PaymentClearingAccount.PUBLIC_ID, Direction.CREDIT, "30.0000");
    assertLeg(execution.postings(), account.ledgerAccountPublicId(), Direction.DEBIT, "2.0000");
    assertLeg(execution.postings(), FeeRevenueAccount.PUBLIC_ID, Direction.CREDIT, "2.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      String payload = eventPayload(st, "payout.settled", payout.publicId());
      assertTrue(payload.contains("\"fee\":\"2.00\""), payload);
      assertTrue(payload.contains("\"netAmount\":\"28.00\""), payload);
    }
  }

  @Test
  void failedTransferReturnsTheReservation() throws Exception {
    var account = fundedAccount(SeedMerchant.PUBLIC_ID, "100.0000");
    var reserveBefore = ledger.balance(PayoutReservedAccount.PUBLIC_ID);
    var payout = payoutOf(SeedMerchant.PUBLIC_ID, account.publicId(), "30.0000", "bank.return-01");

    simulator.failTransfer(payout.transferPublicId());
    var failed = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());

    assertEquals(PayoutStatus.FAILED, failed.status());
    // The reservation is released whole: the merchant is whole again and the
    // pooled reserve reads its pre-request balance (delta counting — the
    // container is shared).
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("100.0000")));
    assertEquals(0, ledger.balance(PayoutReservedAccount.PUBLIC_ID).compareTo(reserveBefore));
    assertNotNull(failed.returnTransactionPublicId());
    assertNull(failed.executeTransactionPublicId());
    assertNull(failed.feeAmount());
    var returned = ledger.getTransaction(failed.returnTransactionPublicId());
    assertEquals("payout " + payout.publicId() + " return", returned.memo());
    assertEquals(2, returned.postings().size(), returned.postings().toString());
    assertLeg(returned.postings(), PayoutReservedAccount.PUBLIC_ID, Direction.DEBIT, "30.0000");
    assertLeg(returned.postings(), account.ledgerAccountPublicId(), Direction.CREDIT, "30.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      String payload = eventPayload(st, "payout.failed", payout.publicId());
      assertFalse(payload.contains("\"fee\""), payload);
      assertFalse(payload.contains("\"netAmount\""), payload);
    }
  }

  @Test
  void expiryReturnsTheReservation() throws Exception {
    var account = fundedAccount(SeedMerchant.PUBLIC_ID, "100.0000");
    var reserveBefore = ledger.balance(PayoutReservedAccount.PUBLIC_ID);
    var payout = payoutOf(SeedMerchant.PUBLIC_ID, account.publicId(), "30.0000", "bank.expire-01");

    // The only way a payout ages past its expiry in-test: backdate the row.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.payout SET expires_at = now() - interval '1 second' "
          + "WHERE public_id = '" + payout.publicId() + "'");
    }
    var expired = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());

    assertEquals(PayoutStatus.EXPIRED, expired.status());
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("100.0000")));
    assertEquals(0, ledger.balance(PayoutReservedAccount.PUBLIC_ID).compareTo(reserveBefore));
    assertNotNull(expired.returnTransactionPublicId());
    var returned = ledger.getTransaction(expired.returnTransactionPublicId());
    assertEquals(2, returned.postings().size(), returned.postings().toString());
    assertLeg(returned.postings(), PayoutReservedAccount.PUBLIC_ID, Direction.DEBIT, "30.0000");
    assertLeg(returned.postings(), account.ledgerAccountPublicId(), Direction.CREDIT, "30.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      assertTrue(eventPayload(st, "payout.expired", payout.publicId()).contains("\"status\":\"EXPIRED\""));
    }

    // A transfer that succeeds only after expiry changes nothing: expiry is
    // terminal and the early return never polls the network again.
    simulator.payTransfer(payout.transferPublicId());
    var still = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());
    assertEquals(PayoutStatus.EXPIRED, still.status());
    assertNull(still.executeTransactionPublicId());
    assertEquals(expired.returnTransactionPublicId(), still.returnTransactionPublicId());
  }

  @Test
  void terminalStatesAreStableAndOwnershipScopes() throws Exception {
    var account = fundedAccount(SeedMerchant.PUBLIC_ID, "100.0000");
    var payout = payoutOf(SeedMerchant.PUBLIC_ID, account.publicId(), "30.0000", "bank.stable-01");
    simulator.payTransfer(payout.transferPublicId());
    var first = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());
    assertEquals(PayoutStatus.SETTLED, first.status());

    // The second get re-reads the terminal row and posts nothing.
    long journalBefore;
    try (var c = adminConnection(); var st = c.createStatement()) {
      journalBefore = journalRowCount(st);
    }
    var second = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());
    assertEquals(first, second);
    try (var c = adminConnection(); var st = c.createStatement()) {
      assertEquals(journalBefore, journalRowCount(st));
    }

    // Ownership scopes the read: another merchant's payout is indistinguishable
    // from an unknown one.
    UUID actingKey = operatorKeys.create("payout-lifecycle-probe", null, null).key().publicId();
    var other = merchants.create("Payout Lifecycle Other", FeeSchedule.ZERO, actingKey);
    assertThrows(UnknownPayoutException.class,
        () -> payouts.get(other.publicId(), payout.publicId()));
  }

  private static void assertLeg(List<PostedPosting> postings, UUID accountPublicId,
      Direction direction, String amount) {
    var matches = postings.stream()
        .filter(p -> accountPublicId.equals(p.accountPublicId()) && p.direction() == direction)
        .toList();
    assertEquals(1, matches.size(), postings.toString());
    assertEquals(0, matches.get(0).amount().compareTo(Money.ofBrl(amount)), postings.toString());
  }

  /** This payout's event of the given type, scoped by public id — the shared
   *  container legitimately holds other classes' events. */
  private static String eventPayload(Statement st, String type, UUID payoutPublicId)
      throws SQLException {
    try (ResultSet rs = st.executeQuery(
        "select payload from webhooks.webhook_event where type = '" + type
            + "' and payload like '%\"" + payoutPublicId + "\"%'")) {
      assertTrue(rs.next());
      return rs.getString("payload");
    }
  }

  private static long journalRowCount(Statement st) throws SQLException {
    try (ResultSet rs = st.executeQuery("select count(*) from ledger.journal_transaction")) {
      assertTrue(rs.next());
      return rs.getLong(1);
    }
  }

  /**
   * The container is shared across classes and the conciliation suites assert
   * over now-relative windows. Push this class's settlements, network rows, and
   * payout fixtures two hours back — the same DB-side rewrite the sibling
   * suites use — so they never fall inside another test's window.
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
      if (!payoutIds.isEmpty()) {
        st.executeUpdate("UPDATE payments.payout SET created_at = now() - interval '2 hours',"
            + " expires_at = expires_at - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(payoutIds) + ")");
        st.executeUpdate("UPDATE payments.payout SET settled_at = settled_at - interval '2 hours'"
            + " WHERE settled_at IS NOT NULL AND public_id IN (" + quoted(payoutIds) + ")");
      }
      if (!networkTransfers.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.payout_transfer"
            + " SET created_at = now() - interval '2 hours', updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(networkTransfers) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
