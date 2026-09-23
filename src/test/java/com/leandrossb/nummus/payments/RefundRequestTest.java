package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.accounts.domain.PayoutsInFlightException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostedPosting;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundReservedAccount;
import com.leandrossb.nummus.payments.application.RefundsRepository;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.InsufficientFundsException;
import com.leandrossb.nummus.payments.domain.IntentNotRefundableException;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.payments.domain.RefundExceedsRemainingException;
import com.leandrossb.nummus.payments.domain.RefundStatus;
import com.leandrossb.nummus.payments.domain.UnknownPaymentIntentException;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The refund request path: settled money is held aside through a journal
 * reservation taken under the same ledger-account row lock payout requests
 * take, before the refund row exists. Service-level on purpose — the HTTP
 * surface arrives with Task 6.
 */
class RefundRequestTest extends IntegrationTestBase {

  /** Fixtures this class settles/charges/refunds — backdated in
   *  {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  private static final List<UUID> refundIds = new ArrayList<>();

  private static final List<UUID> networkRefunds = new ArrayList<>();

  private static final List<UUID> payoutIds = new ArrayList<>();

  private static final List<UUID> networkTransfers = new ArrayList<>();

  @Autowired
  private RefundsService refunds;
  @Autowired
  private RefundsRepository refundRows;
  @Autowired
  private PaymentsService payments;
  @Autowired
  private PayoutsService payouts;
  @Autowired
  private BankAccountsService bankAccounts;
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
   * payout suite uses (open account, create intent, pay the charge, first
   * read settles). SeedMerchant carries the zero fee schedule, so the full
   * gross lands as available balance.
   */
  private PaymentIntent settledIntent(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("Refund Merchant"));
    return settle(account, amount);
  }

  /** One more settled intent on an existing account — extra funding capacity. */
  private PaymentIntent settle(PaymentAccount account, String amount) {
    var intent = payments.create(account.merchantPublicId(),
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    return payments.get(account.merchantPublicId(), intent.publicId());
  }

  /** A merchant with no relation to the fixtures — the tenancy probe. */
  private UUID foreignMerchant() {
    UUID actingKey = operatorKeys.create("refund-request-probe", null, null).key().publicId();
    return merchants.create("Refund Request Foreign " + UUID.randomUUID(),
        FeeSchedule.ZERO, actingKey).publicId();
  }

  /** Registers the refund and its network refund for the class-end sweep. */
  private Refund register(Refund refund) {
    refundIds.add(refund.publicId());
    networkRefunds.add(refund.networkRefundPublicId());
    return refund;
  }

  /** Registers the payout and its transfer for the class-end sweep. */
  private Payout register(Payout payout) {
    payoutIds.add(payout.publicId());
    networkTransfers.add(payout.transferPublicId());
    return payout;
  }

  @Test
  void requestHoldsTheExactAmount() {
    var intent = settledIntent("100.0000");
    var account = accountsService.get(SeedMerchant.PUBLIC_ID, intent.accountPublicId());
    var reserveBefore = ledger.balance(RefundReservedAccount.PUBLIC_ID);

    var refund = register(refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl("30.0000"), null)));

    assertEquals(RefundStatus.REQUESTED, refund.status());
    assertEquals(intent.publicId(), refund.intentPublicId());
    assertEquals(0, refund.amount().compareTo(Money.ofBrl("30.0000")));
    assertNotNull(refund.networkRefundPublicId());
    assertNotNull(refund.holdTransactionPublicId());
    assertNull(refund.settledAt());
    assertNull(refund.executeTransactionPublicId());
    assertNull(refund.returnTransactionPublicId());
    // The hold moved the funds aside: the available balance drops to 70.00...
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("70.0000")));
    // ...and the pooled reserve grew by exactly 30.00. Ledger.balance is raw
    // (debits minus credits); the reserve is a LIABILITY whose normal side is
    // CREDIT, so the hold reads -30.0000 raw — positive 30.00 in its natural
    // sign. Asserted as a delta: the reserve is pooled, other holds of this
    // class may already sit on it.
    assertEquals(0, ledger.balance(RefundReservedAccount.PUBLIC_ID).subtract(reserveBefore)
        .compareTo(Money.ofBrl("-30.0000")));
    // The hold transaction carries the two legs verbatim: debit the
    // merchant's ledger account, credit the reserve.
    var hold = ledger.getTransaction(refund.holdTransactionPublicId());
    assertEquals(2, hold.postings().size(), hold.postings().toString());
    assertLeg(hold.postings(), account.ledgerAccountPublicId(), Direction.DEBIT, "30.0000");
    assertLeg(hold.postings(), RefundReservedAccount.PUBLIC_ID, Direction.CREDIT, "30.0000");
  }

  @Test
  void refundExceedingRemainingIsRejected() throws Exception {
    var intent = settledIntent("100.0000");
    register(refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl("70.0000"), null)));
    long journalBefore = count("select count(*) from ledger.journal_transaction");

    // 50.00 of the 30.00 that remains refundable — and note the balance guard
    // would reject this too (30.00 available): the exception type pins that
    // the refundable-remainder guard fires first.
    var ex = assertThrows(RefundExceedsRemainingException.class, () -> refunds.create(
        SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl("50.0000"), null)));

    assertEquals(intent.publicId(), ex.intentPublicId());
    assertEquals(0, ex.remaining().compareTo(Money.ofBrl("30.0000")));
    assertEquals(0, ex.requested().compareTo(Money.ofBrl("50.0000")));
    // Zero side effects: no second refund row, no second network refund,
    // no journal entry.
    assertEquals(1, count("select count(*) from payments.refund"
        + " where intent_public_id = '" + intent.publicId() + "'"));
    assertEquals(1, count("select count(*) from psp_simulator.charge_refund"
        + " where charge_public_id = '" + intent.chargePublicId() + "'"));
    assertEquals(journalBefore, count("select count(*) from ledger.journal_transaction"));
  }

  @Test
  void insufficientFundsIsRejectedBeforeAnyPosting() throws Exception {
    var intent = settledIntent("100.0000");
    var account = accountsService.get(SeedMerchant.PUBLIC_ID, intent.accountPublicId());
    var bankAccount = ApiDrivers.registerVerifiedBankAccount(bankAccounts,
        SeedMerchant.PUBLIC_ID);
    register(payouts.create(SeedMerchant.PUBLIC_ID, new CreatePayoutCommand(
        account.publicId(), Money.ofBrl("70.0000"), bankAccount.publicId(), null)));
    long journalBefore = count("select count(*) from ledger.journal_transaction");

    // The shared lock's other tenant: the payout reservation already drew the
    // available balance down to 30.00, while the intent's 100.00 is still
    // fully refundable — the funds guard is what must reject this.
    var ex = assertThrows(InsufficientFundsException.class, () -> refunds.create(
        SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl("50.0000"), null)));

    assertEquals(account.publicId(), ex.accountPublicId());
    assertEquals(0, ex.available().compareTo(Money.ofBrl("30.0000")));
    assertEquals(0, ex.requested().compareTo(Money.ofBrl("50.0000")));
    // Zero side effects: no refund row, no network refund, no journal entry.
    assertEquals(0, count("select count(*) from payments.refund"
        + " where intent_public_id = '" + intent.publicId() + "'"));
    assertEquals(0, count("select count(*) from psp_simulator.charge_refund"
        + " where charge_public_id = '" + intent.chargePublicId() + "'"));
    assertEquals(journalBefore, count("select count(*) from ledger.journal_transaction"));
  }

  @Test
  void onlySettledIntentsRefund() throws Exception {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("Refund Status Merchant"));
    var created = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("10.0000"), null));
    networkCharges.add(created.chargePublicId());

    var notSettled = assertThrows(IntentNotRefundableException.class, () -> refunds.create(
        SeedMerchant.PUBLIC_ID, created.publicId(),
        new CreateRefundCommand(Money.ofBrl("1.0000"), null)));
    assertEquals(created.publicId(), notSettled.intentPublicId());
    assertEquals(IntentStatus.CREATED, notSettled.status());

    // Terminate the intent the lazy way — backdate its expiry, then read it.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.payment_intent SET expires_at = now() - interval '1 second'"
          + " WHERE public_id = '" + created.publicId() + "'");
    }
    var expired = payments.get(SeedMerchant.PUBLIC_ID, created.publicId());
    assertEquals(IntentStatus.EXPIRED, expired.status());
    var notRefundable = assertThrows(IntentNotRefundableException.class, () -> refunds.create(
        SeedMerchant.PUBLIC_ID, created.publicId(),
        new CreateRefundCommand(Money.ofBrl("1.0000"), null)));
    assertEquals(IntentStatus.EXPIRED, notRefundable.status());

    // Tenancy vocabulary: another merchant's intent is indistinguishable from
    // an unknown one.
    assertThrows(UnknownPaymentIntentException.class, () -> refunds.create(
        foreignMerchant(), created.publicId(),
        new CreateRefundCommand(Money.ofBrl("1.0000"), null)));
  }

  /**
   * The concurrent invariant the shared fence buys: N simultaneous refunds of
   * one intent may interleave any way they like — exactly one hold may come
   * out of it. The account is funded to 200.00 so funds never bind and the
   * refundable remainder is the only constraint: the winner takes 70.00 of
   * the intent's 100.00 and every other request must derive from that hold,
   * not the pristine intent, and be rejected. No barriers or timing
   * assertions: the outcome is deterministic while the fence holds, and
   * without it several requests read the same pristine remainder and
   * over-refund the charge.
   */
  @Test
  void concurrentRefundsSerializeOnTheSharedLock() throws Exception {
    var intent = settledIntent("100.0000");
    var account = accountsService.get(SeedMerchant.PUBLIC_ID, intent.accountPublicId());
    settle(account, "100.0000");
    int requests = 3;
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    try {
      List<Future<Refund>> futures = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        futures.add(pool.submit(() -> refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
            new CreateRefundCommand(Money.ofBrl("70.0000"), null))));
      }
      List<Refund> winners = new ArrayList<>();
      List<RefundExceedsRemainingException> rejected = new ArrayList<>();
      for (Future<Refund> future : futures) {
        try {
          winners.add(register(future.get(60, TimeUnit.SECONDS)));
        } catch (ExecutionException e) {
          if (e.getCause() instanceof RefundExceedsRemainingException exceeds) {
            rejected.add(exceeds);
          } else {
            throw e;
          }
        }
      }
      String outcome = "succeeded=" + winners.size() + " rejected=" + rejected.size();
      assertEquals(1, winners.size(), outcome);
      assertEquals(requests - 1, rejected.size(), outcome);
      assertEquals(RefundStatus.REQUESTED, winners.get(0).status());
      for (RefundExceedsRemainingException exceeds : rejected) {
        assertEquals(0, exceeds.remaining().compareTo(Money.ofBrl("30.0000")), exceeds.getMessage());
        assertEquals(0, exceeds.requested().compareTo(Money.ofBrl("70.0000")), exceeds.getMessage());
      }
      // The database agrees with the calls: exactly one REQUESTED refund row,
      // whichever request won the fence.
      assertEquals(1, count("select count(*) from payments.refund"
          + " where intent_public_id = '" + intent.publicId() + "'"
          + " and status = 'REQUESTED'"), outcome);
      // And the intent's refundable total is the single 70.00 hold.
      assertEquals(0, refundRows.refundedTotal(intent.publicId())
          .compareTo(Money.ofBrl("70.0000")), outcome);
      // Funds never bound: 200.00 funded, one 70.00 hold — 130.00 remains.
      assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
          .compareTo(Money.ofBrl("130.0000")), outcome);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * The single-lock discipline across BOTH hold families, mixed: refunds and
   * payouts serialize on the same ledger-account row, so a mixed burst
   * against one account lands exactly one hold regardless of interleaving.
   * The account is funded to exactly 100.00 by one settle (refundable 100
   * AND available 100); two payouts.create of 70.00 and two refunds.create
   * of 70.00 race, and whichever request wins the fence leaves 30.00 — the
   * other three are rejected by their family's guard (InsufficientFunds, or
   * RefundExceedsRemaining when a refund won). Counterfactual the pin
   * guards: without the shared fence the two families would interleave
   * their reads independently and land two holds — 140.00 against 100.00,
   * an overdraft. No barriers: outcome assertions only.
   */
  @Test
  void mixedPayoutsAndRefundsLandExactlyOneHold() throws Exception {
    var intent = settledIntent("100.0000");
    var account = accountsService.get(SeedMerchant.PUBLIC_ID, intent.accountPublicId());
    var bankAccount = ApiDrivers.registerVerifiedBankAccount(bankAccounts,
        SeedMerchant.PUBLIC_ID);
    int requests = 4;
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        boolean payout = i % 2 == 0;
        futures.add(pool.submit(() -> {
          if (payout) {
            return (Object) payouts.create(SeedMerchant.PUBLIC_ID, new CreatePayoutCommand(
                account.publicId(), Money.ofBrl("70.0000"), bankAccount.publicId(), null));
          }
          return (Object) refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
              new CreateRefundCommand(Money.ofBrl("70.0000"), null));
        }));
      }
      int landed = 0;
      Object hold = null;
      List<Throwable> rejected = new ArrayList<>();
      for (Future<Object> future : futures) {
        try {
          hold = future.get(60, TimeUnit.SECONDS);
          landed++;
        } catch (ExecutionException e) {
          if (e.getCause() instanceof InsufficientFundsException
              || e.getCause() instanceof RefundExceedsRemainingException) {
            rejected.add(e.getCause());
          } else {
            throw e;
          }
        }
      }
      String outcome = "landed=" + landed + " rejected=" + rejected.size();
      assertEquals(1, landed, outcome);
      assertEquals(requests - 1, rejected.size(), outcome);
      if (hold instanceof Payout payout) {
        register(payout);
        assertEquals(PayoutStatus.REQUESTED, payout.status(), outcome);
      } else {
        var refund = (Refund) hold;
        register(refund);
        assertEquals(RefundStatus.REQUESTED, refund.status(), outcome);
      }
      // The database agrees whichever family won: exactly one REQUESTED hold
      // row across payments.payout and payments.refund for this account.
      assertEquals(1, count("select (select count(*) from payments.payout"
          + " where account_public_id = '" + account.publicId() + "' and status = 'REQUESTED')"
          + " + (select count(*) from payments.refund r join payments.payment_intent i"
          + " on r.intent_public_id = i.public_id"
          + " where i.account_public_id = '" + account.publicId() + "'"
          + " and r.status = 'REQUESTED')"), outcome);
      // And no overdraft: 100.00 funded, one 70.00 hold — exactly 30.00 left.
      assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
          .compareTo(Money.ofBrl("30.0000")), outcome);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void freezeAndCloseRejectWithARefundInFlight() throws Exception {
    var intent = settledIntent("100.0000");
    var account = accountsService.get(SeedMerchant.PUBLIC_ID, intent.accountPublicId());
    var refund = register(refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl("30.0000"), null)));

    // The generalized in-flight guard: a REQUESTED hold — a refund, not a
    // payout — blocks the transition. PayoutsInFlightException keeps its
    // name; the semantic is any hold still in flight on the account.
    assertThrows(PayoutsInFlightException.class,
        () -> accountsService.freeze(SeedMerchant.PUBLIC_ID, account.publicId()));
    // The rejected transition left nothing behind: the payment account and
    // its backing ledger account stay ACTIVE...
    assertEquals(AccountStatus.ACTIVE,
        accountsService.get(SeedMerchant.PUBLIC_ID, account.publicId()).status());
    assertEquals(com.leandrossb.nummus.ledger.domain.AccountStatus.ACTIVE,
        ledger.getAccount(account.ledgerAccountPublicId()).status());
    // ...and the refund is untouched and readable.
    var held = refundRows.findByPublicId(refund.publicId()).orElseThrow();
    assertEquals(RefundStatus.REQUESTED, held.status());
    assertEquals(0, held.amount().compareTo(Money.ofBrl("30.0000")));

    assertThrows(PayoutsInFlightException.class,
        () -> accountsService.close(SeedMerchant.PUBLIC_ID, account.publicId()));
    assertEquals(AccountStatus.ACTIVE,
        accountsService.get(SeedMerchant.PUBLIC_ID, account.publicId()).status());

    // Once the refund is terminal the freeze goes through. The lazy read that
    // drives expiry arrives with the lifecycle task; here the terminal flip
    // is the fixture itself — the guard reads the row status.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.refund SET status = 'EXPIRED',"
          + " expires_at = now() - interval '1 second'"
          + " WHERE public_id = '" + refund.publicId() + "'");
    }
    assertEquals(RefundStatus.EXPIRED,
        refundRows.findByPublicId(refund.publicId()).orElseThrow().status());
    assertEquals(AccountStatus.FROZEN,
        accountsService.freeze(SeedMerchant.PUBLIC_ID, account.publicId()).status());
  }

  private static void assertLeg(List<PostedPosting> postings, UUID accountPublicId,
      Direction direction, String amount) {
    var matches = postings.stream()
        .filter(p -> accountPublicId.equals(p.accountPublicId()) && p.direction() == direction)
        .toList();
    assertEquals(1, matches.size(), postings.toString());
    assertEquals(0, matches.get(0).amount().compareTo(Money.ofBrl(amount)), postings.toString());
  }

  private static long count(String sql) throws SQLException {
    try (var c = adminConnection(); var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      assertTrue(rs.next());
      return rs.getLong(1);
    }
  }

  /**
   * The container is shared across classes and the conciliation suites assert
   * over now-relative windows. Push this class's settlements, network
   * charges, refunds, and payout fixtures two hours back — the same DB-side
   * rewrite the sibling suites use — so they never fall inside another
   * test's window.
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
      if (!payoutIds.isEmpty()) {
        st.executeUpdate("UPDATE payments.payout SET created_at = now() - interval '2 hours',"
            + " expires_at = expires_at - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(payoutIds) + ")");
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
