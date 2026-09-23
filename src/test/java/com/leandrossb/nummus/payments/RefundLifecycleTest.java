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
import com.leandrossb.nummus.payments.application.RefundReservedAccount;
import com.leandrossb.nummus.payments.application.RefundsRepository;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.payments.domain.RefundStatus;
import com.leandrossb.nummus.payments.domain.UnknownRefundException;
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
 * The lazy refund lifecycle: the first {@code get} after the network moves the
 * charge refund settles or returns the hold, posts the terminal journal legs,
 * and publishes the lifecycle event. Service-level on purpose — the HTTP
 * surface arrives with Task 6.
 */
class RefundLifecycleTest extends IntegrationTestBase {

  /** Fixtures this class settles/charges/refunds — backdated in
   *  {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  private static final List<UUID> refundIds = new ArrayList<>();

  private static final List<UUID> networkRefunds = new ArrayList<>();

  @Autowired
  private RefundsService refunds;
  @Autowired
  private RefundsRepository refundRows;
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

  /**
   * A settled intent on a freshly opened account — the settle recipe the
   * refund request suite uses (open account, create intent, pay the charge,
   * first read settles). SeedMerchant carries the zero fee schedule, so the
   * full gross lands as available balance.
   */
  private PaymentIntent settledIntent(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("Refund Lifecycle Merchant"));
    return settle(SeedMerchant.PUBLIC_ID, account, amount);
  }

  /** An account under a merchant whose schedule prices every settlement at a
   *  fixed fee — the retained-fee fixture. */
  private PaymentAccount fixedFeeAccount(String fixedFee) {
    UUID actingKey = operatorKeys.create("refund-lifecycle-probe", null, null).key().publicId();
    var merchant = merchants.create("Refund Lifecycle Fee " + UUID.randomUUID(),
        new FeeSchedule(BigDecimal.ZERO, new BigDecimal(fixedFee), BigDecimal.ZERO), actingKey)
        .publicId();
    return accountsService.open(merchant, new OpenAccountCommand("Refund Lifecycle Fee Account"));
  }

  private PaymentIntent settle(UUID merchantPublicId, PaymentAccount account, String amount) {
    var intent = payments.create(merchantPublicId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    return payments.get(merchantPublicId, intent.publicId());
  }

  /** Registers the refund and its network refund for the class-end sweep. */
  private Refund register(Refund refund) {
    refundIds.add(refund.publicId());
    networkRefunds.add(refund.networkRefundPublicId());
    return refund;
  }

  private Refund refundOf(UUID merchantPublicId, UUID intentPublicId, String amount) {
    return register(refunds.create(merchantPublicId, intentPublicId,
        new CreateRefundCommand(Money.ofBrl(amount), null)));
  }

  @Test
  void executedRefundSettlesAndReturnsTheMoney() throws Exception {
    var intent = settledIntent("100.0000");
    var account = accountsService.get(SeedMerchant.PUBLIC_ID, intent.accountPublicId());
    var clearingBefore = ledger.balance(PaymentClearingAccount.PUBLIC_ID);
    var refund = refundOf(SeedMerchant.PUBLIC_ID, intent.publicId(), "30.0000");

    simulator.payRefund(refund.networkRefundPublicId());
    var settled = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());

    assertEquals(RefundStatus.SETTLED, settled.status());
    assertEquals(refund.publicId(), settled.publicId());
    // The hold already debited the merchant at request; settling moves the
    // reserve back to the network and never touches the merchant's balance.
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("70.0000")));
    // Clearing is a debit-normal asset: the execution credits it, so its raw
    // balance drops by exactly the refund amount (delta — the account is pooled).
    assertEquals(0, clearingBefore.subtract(ledger.balance(PaymentClearingAccount.PUBLIC_ID))
        .compareTo(Money.ofBrl("30.0000")));
    // The row carries the execution link, and the link posts the two legs
    // verbatim: debit the reserve, credit clearing. A refund never posts a
    // fee leg — processing fees are retained, not reversed.
    assertNotNull(settled.executeTransactionPublicId());
    assertNull(settled.returnTransactionPublicId());
    assertNotNull(settled.settledAt());
    var execution = ledger.getTransaction(settled.executeTransactionPublicId());
    assertEquals("refund " + refund.publicId() + " execute", execution.memo());
    assertEquals(2, execution.postings().size(), execution.postings().toString());
    assertLeg(execution.postings(), RefundReservedAccount.PUBLIC_ID, Direction.DEBIT, "30.0000");
    assertLeg(execution.postings(), PaymentClearingAccount.PUBLIC_ID, Direction.CREDIT, "30.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      String payload = eventPayload(st, "refund.settled", refund.publicId());
      assertTrue(payload.contains("\"refundId\":\"" + refund.publicId() + "\""), payload);
      assertTrue(payload.contains("\"intentId\":\"" + intent.publicId() + "\""), payload);
      assertTrue(payload.contains("\"amount\":\"30.00\""), payload);
      assertFalse(payload.contains("\"fee\""), payload);
      assertFalse(payload.contains("\"netAmount\""), payload);
    }
  }

  @Test
  void failedRefundReturnsTheHold() throws Exception {
    var intent = settledIntent("100.0000");
    var account = accountsService.get(SeedMerchant.PUBLIC_ID, intent.accountPublicId());
    var reserveBefore = ledger.balance(RefundReservedAccount.PUBLIC_ID);
    var refund = refundOf(SeedMerchant.PUBLIC_ID, intent.publicId(), "30.0000");

    simulator.failRefund(refund.networkRefundPublicId());
    var failed = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());

    assertEquals(RefundStatus.FAILED, failed.status());
    // The hold is released whole: the merchant is whole again and the pooled
    // reserve reads its pre-request balance (delta counting — the container
    // is shared).
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("100.0000")));
    assertEquals(0, ledger.balance(RefundReservedAccount.PUBLIC_ID).compareTo(reserveBefore));
    assertNotNull(failed.returnTransactionPublicId());
    assertNull(failed.executeTransactionPublicId());
    assertNull(failed.settledAt());
    var returned = ledger.getTransaction(failed.returnTransactionPublicId());
    assertEquals("refund " + refund.publicId() + " return", returned.memo());
    assertEquals(2, returned.postings().size(), returned.postings().toString());
    assertLeg(returned.postings(), RefundReservedAccount.PUBLIC_ID, Direction.DEBIT, "30.0000");
    assertLeg(returned.postings(), account.ledgerAccountPublicId(), Direction.CREDIT, "30.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      String payload = eventPayload(st, "refund.failed", refund.publicId());
      assertTrue(payload.contains("\"status\":\"FAILED\""), payload);
      assertFalse(payload.contains("\"fee\""), payload);
    }
  }

  @Test
  void expiryReturnsTheHold() throws Exception {
    var intent = settledIntent("100.0000");
    var account = accountsService.get(SeedMerchant.PUBLIC_ID, intent.accountPublicId());
    var reserveBefore = ledger.balance(RefundReservedAccount.PUBLIC_ID);
    var refund = refundOf(SeedMerchant.PUBLIC_ID, intent.publicId(), "30.0000");

    // The only way a refund ages past its expiry in-test: backdate the row.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.refund SET expires_at = now() - interval '1 second' "
          + "WHERE public_id = '" + refund.publicId() + "'");
    }
    var expired = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());

    assertEquals(RefundStatus.EXPIRED, expired.status());
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("100.0000")));
    assertEquals(0, ledger.balance(RefundReservedAccount.PUBLIC_ID).compareTo(reserveBefore));
    assertNotNull(expired.returnTransactionPublicId());
    assertNull(expired.executeTransactionPublicId());
    var returned = ledger.getTransaction(expired.returnTransactionPublicId());
    assertEquals("refund " + refund.publicId() + " return", returned.memo());
    assertEquals(2, returned.postings().size(), returned.postings().toString());
    assertLeg(returned.postings(), RefundReservedAccount.PUBLIC_ID, Direction.DEBIT, "30.0000");
    assertLeg(returned.postings(), account.ledgerAccountPublicId(), Direction.CREDIT, "30.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      assertTrue(eventPayload(st, "refund.expired", refund.publicId()).contains("\"status\":\"EXPIRED\""));
    }

    // A network refund that succeeds only after expiry changes nothing: expiry
    // is terminal and the early return never polls the network again.
    simulator.payRefund(refund.networkRefundPublicId());
    var still = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());
    assertEquals(RefundStatus.EXPIRED, still.status());
    assertNull(still.executeTransactionPublicId());
    assertEquals(expired.returnTransactionPublicId(), still.returnTransactionPublicId());
  }

  @Test
  void terminalStatesAreStableAndOwnershipScopes() throws Exception {
    var intent = settledIntent("100.0000");
    var refund = refundOf(SeedMerchant.PUBLIC_ID, intent.publicId(), "30.0000");
    simulator.payRefund(refund.networkRefundPublicId());
    var first = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());
    assertEquals(RefundStatus.SETTLED, first.status());

    // The second get re-reads the terminal row and posts nothing.
    long journalBefore;
    try (var c = adminConnection(); var st = c.createStatement()) {
      journalBefore = journalRowCount(st);
    }
    var second = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());
    assertEquals(first, second);
    try (var c = adminConnection(); var st = c.createStatement()) {
      assertEquals(journalBefore, journalRowCount(st));
    }

    // Ownership scopes the read: another merchant's refund is indistinguishable
    // from an unknown one.
    UUID actingKey = operatorKeys.create("refund-lifecycle-probe", null, null).key().publicId();
    var other = merchants.create("Refund Lifecycle Other", FeeSchedule.ZERO, actingKey);
    assertThrows(UnknownRefundException.class,
        () -> refunds.get(other.publicId(), refund.publicId()));
  }

  /**
   * The retained-fee bound, end to end: a settlement priced at a 2.00 fixed
   * fee nets the merchant 98.00 of a 100.00 gross; refunding that full net
   * settles without ever touching revenue. Nothing else funds the account —
   * available is exactly the settlement's net — so the refund proving out at
   * 98.00 (never 100.00) pins that fees are retained across refunds.
   */
  @Test
  void feeIsRetainedAcrossAFullRefund() throws Exception {
    var account = fixedFeeAccount("2.00");
    var merchant = account.merchantPublicId();
    var intent = settle(merchant, account, "100.0000");
    var feeRevenueAfterSettle = ledger.balance(FeeRevenueAccount.PUBLIC_ID);
    assertEquals(0, accountsService.balance(merchant, account.publicId())
        .compareTo(Money.ofBrl("98.0000")));

    var refund = refundOf(merchant, intent.publicId(), "98.0000");
    simulator.payRefund(refund.networkRefundPublicId());
    var settled = refunds.get(merchant, refund.publicId());

    assertEquals(RefundStatus.SETTLED, settled.status());
    assertEquals(0, accountsService.balance(merchant, account.publicId())
        .compareTo(Money.ofBrl("0.0000")));
    // Revenue is untouched by the refund: the fee stays booked from the
    // settlement (credit-normal pooled account, asserted as a raw equality).
    assertEquals(0, ledger.balance(FeeRevenueAccount.PUBLIC_ID)
        .compareTo(feeRevenueAfterSettle));
    var execution = ledger.getTransaction(settled.executeTransactionPublicId());
    assertEquals(2, execution.postings().size(), execution.postings().toString());
    assertLeg(execution.postings(), RefundReservedAccount.PUBLIC_ID, Direction.DEBIT, "98.0000");
    assertLeg(execution.postings(), PaymentClearingAccount.PUBLIC_ID, Direction.CREDIT, "98.0000");
    // The intent's refunded total carries the executed refund.
    assertEquals(0, refundRows.refundedTotal(intent.publicId())
        .compareTo(Money.ofBrl("98.0000")));
  }

  private static void assertLeg(List<PostedPosting> postings, UUID accountPublicId,
      Direction direction, String amount) {
    var matches = postings.stream()
        .filter(p -> accountPublicId.equals(p.accountPublicId()) && p.direction() == direction)
        .toList();
    assertEquals(1, matches.size(), postings.toString());
    assertEquals(0, matches.get(0).amount().compareTo(Money.ofBrl(amount)), postings.toString());
  }

  /** This refund's event of the given type, scoped by public id — the shared
   *  container legitimately holds other classes' events. */
  private static String eventPayload(Statement st, String type, UUID refundPublicId)
      throws SQLException {
    try (ResultSet rs = st.executeQuery(
        "select payload from webhooks.webhook_event where type = '" + type
            + "' and payload like '%\"" + refundPublicId + "\"%'")) {
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
   * over now-relative windows. Push this class's settlements, network rows,
   * and refund fixtures two hours back — the same DB-side rewrite the sibling
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
      if (!refundIds.isEmpty()) {
        st.executeUpdate("UPDATE payments.refund SET created_at = now() - interval '2 hours',"
            + " expires_at = expires_at - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(refundIds) + ")");
        st.executeUpdate("UPDATE payments.refund SET settled_at = settled_at - interval '2 hours'"
            + " WHERE settled_at IS NOT NULL AND public_id IN (" + quoted(refundIds) + ")");
      }
      if (!networkRefunds.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge_refund"
            + " SET created_at = now() - interval '2 hours', updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(networkRefunds) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
