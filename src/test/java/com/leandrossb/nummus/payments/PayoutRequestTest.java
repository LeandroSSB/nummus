package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostedPosting;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutReservedAccount;
import com.leandrossb.nummus.payments.application.PayoutsRepository;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.InsufficientFundsException;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The payout request path: funds leave the merchant's available balance through a
 * journal reservation taken under the ledger-account row lock, before the payout
 * row exists. Service-level on purpose — the HTTP surface arrives with Task 6.
 */
class PayoutRequestTest extends IntegrationTestBase {

  /** Fixtures this class settles/charges/transfers — backdated in
   *  {@link #moveFixturesOutOfNowWindows()}. */
  private static final List<UUID> settledIntents = new ArrayList<>();

  private static final List<UUID> networkCharges = new ArrayList<>();

  private static final List<UUID> payoutIds = new ArrayList<>();

  private static final List<UUID> networkTransfers = new ArrayList<>();

  @Autowired
  private PayoutsService payouts;
  @Autowired
  private PayoutsRepository payoutRows;
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
   * A payment account funded by one settled intent — the settle recipe the
   * conciliation suites use (create intent, pay the charge, first poll settles).
   */
  private PaymentAccount fundedAccount(String amount) {
    return fundedAccount(SeedMerchant.PUBLIC_ID, amount);
  }

  private PaymentAccount fundedAccount(UUID merchantPublicId, String amount) {
    var account = accountsService.open(merchantPublicId, new OpenAccountCommand("Payout Merchant"));
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
    UUID actingKey = operatorKeys.create("payout-request-probe", null, null).key().publicId();
    return merchants.create("Payout Request Fee " + UUID.randomUUID(),
        new FeeSchedule(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(payoutFixed)), actingKey)
        .publicId();
  }

  /** Registers the payout and its transfer for the class-end sweep. */
  private Payout register(Payout payout) {
    payoutIds.add(payout.publicId());
    networkTransfers.add(payout.transferPublicId());
    return payout;
  }

  @Test
  void requestReservesTheExactAmount() {
    var account = fundedAccount("100.0000");
    var reserveBefore = ledger.balance(PayoutReservedAccount.PUBLIC_ID);

    var payout = payouts.create(SeedMerchant.PUBLIC_ID, new CreatePayoutCommand(
        account.publicId(), Money.ofBrl("30.0000"), "bank.main-01", null));

    assertEquals(PayoutStatus.REQUESTED, payout.status());
    assertEquals(account.publicId(), payout.accountPublicId());
    assertEquals(0, payout.amount().compareTo(Money.ofBrl("30.0000")));
    assertNotNull(payout.transferPublicId());
    // The reservation moved the funds aside: the available balance drops to 70.00...
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("70.0000")));
    // ...and the pooled reserve grew by exactly 30.00. Ledger.balance is raw
    // (debits minus credits); the reserve is a LIABILITY whose normal side is
    // CREDIT, so the reservation reads -30.0000 raw — positive 30.00 in its
    // natural sign. Asserted as a delta: the reserve is pooled, other payouts
    // of this class may already sit on it.
    assertEquals(0, ledger.balance(PayoutReservedAccount.PUBLIC_ID).subtract(reserveBefore)
        .compareTo(Money.ofBrl("-30.0000")));
    // The payout row carries the reservation transaction, and it posts the
    // two legs verbatim: debit the merchant's ledger account, credit the reserve.
    var reservation = ledger.getTransaction(payout.requestTransactionPublicId());
    assertEquals(2, reservation.postings().size(), reservation.postings().toString());
    assertLeg(reservation.postings(), account.ledgerAccountPublicId(), Direction.DEBIT, "30.0000");
    assertLeg(reservation.postings(), PayoutReservedAccount.PUBLIC_ID, Direction.CREDIT, "30.0000");
  }

  @Test
  void insufficientFundsIsRejectedBeforeAnyPosting() throws Exception {
    var account = fundedAccount("10.0000");
    String bankKey = "bank.reject-" + UUID.randomUUID();

    var ex = assertThrows(InsufficientFundsException.class, () -> payouts.create(
        SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl("30.0000"), bankKey, null)));

    assertEquals(account.publicId(), ex.accountPublicId());
    assertEquals(0, ex.available().compareTo(Money.ofBrl("10.0000")));
    assertEquals(0, ex.requested().compareTo(Money.ofBrl("30.0000")));
    // The available balance is unchanged...
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("10.0000")));
    // ...and nothing was written: no payout row, no outbound transfer.
    try (var c = adminConnection(); var st = c.createStatement()) {
      try (var rs = st.executeQuery("select count(*) from payments.payout "
          + "where account_public_id = '" + account.publicId() + "'")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
      }
      try (var rs = st.executeQuery("select count(*) from psp_simulator.payout_transfer "
          + "where destination_bank_key = '" + bankKey + "'")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
      }
    }
  }

  @Test
  void secondRequestSeesTheReducedBalance() {
    var account = fundedAccount("100.0000");

    var first = payouts.create(SeedMerchant.PUBLIC_ID, new CreatePayoutCommand(
        account.publicId(), Money.ofBrl("70.0000"), "bank.first", null));
    assertEquals(PayoutStatus.REQUESTED, first.status());

    // The observable outcome of the race the row lock serializes: the second
    // request derives from the first's reservation, not the original balance.
    var ex = assertThrows(InsufficientFundsException.class, () -> payouts.create(
        SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl("70.0000"), "bank.second", null)));
    assertEquals(0, ex.available().compareTo(Money.ofBrl("30.0000")));
    assertEquals(0, ex.requested().compareTo(Money.ofBrl("70.0000")));

    // The first payout is intact and still holds its reservation.
    assertEquals(PayoutStatus.REQUESTED,
        payoutRows.findByPublicId(first.publicId()).orElseThrow().status());
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("30.0000")));
  }

  @Test
  void requestReservesAmountPlusTheScheduleFee() throws Exception {
    var merchant = payoutFeeMerchant("2.00");
    var account = fundedAccount(merchant, "100.0000");
    String bankKey = "bank.fee-reserve-" + UUID.randomUUID();
    long journalBefore;
    try (var c = adminConnection(); var st = c.createStatement()) {
      try (var rs = st.executeQuery("select count(*) from ledger.journal_transaction")) {
        assertTrue(rs.next());
        journalBefore = rs.getLong(1);
      }
    }

    // A full-balance request must price in the execution fee: accepting it
    // would land the merchant at exactly -fee once the fee leg posts.
    var ex = assertThrows(InsufficientFundsException.class, () -> payouts.create(
        merchant, new CreatePayoutCommand(
            account.publicId(), Money.ofBrl("100.0000"), bankKey, null)));
    assertEquals(account.publicId(), ex.accountPublicId());
    assertEquals(0, ex.available().compareTo(Money.ofBrl("100.0000")));
    assertEquals(0, ex.requested().compareTo(Money.ofBrl("102.0000")));
    // The available balance is unchanged...
    assertEquals(0, accountsService.balance(merchant, account.publicId())
        .compareTo(Money.ofBrl("100.0000")));
    // ...and nothing was written: no payout row, no outbound transfer, no journal.
    try (var c = adminConnection(); var st = c.createStatement()) {
      try (var rs = st.executeQuery("select count(*) from payments.payout "
          + "where account_public_id = '" + account.publicId() + "'")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
      }
      try (var rs = st.executeQuery("select count(*) from psp_simulator.payout_transfer "
          + "where destination_bank_key = '" + bankKey + "'")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
      }
      try (var rs = st.executeQuery("select count(*) from ledger.journal_transaction")) {
        assertTrue(rs.next());
        assertEquals(journalBefore, rs.getLong(1));
      }
    }

    // At 98.00 the request fits amount+fee exactly. The reservation still
    // debits only the amount — the fee is an execution-time fact — so 2.00 of
    // capacity remains, reserved for the fee leg that executing will post.
    var payout = register(payouts.create(merchant, new CreatePayoutCommand(
        account.publicId(), Money.ofBrl("98.0000"), bankKey, null)));
    assertEquals(PayoutStatus.REQUESTED, payout.status());
    assertEquals(0, accountsService.balance(merchant, account.publicId())
        .compareTo(Money.ofBrl("2.0000")));

    // Executing the full-capacity payout lands the merchant at exactly 0.00 —
    // the overdraft the fee-blind reservation allowed is gone.
    simulator.payTransfer(payout.transferPublicId());
    var settled = payouts.get(merchant, payout.publicId());
    assertEquals(PayoutStatus.SETTLED, settled.status());
    assertEquals(0, settled.feeAmount().compareTo(Money.ofBrl("2.0000")));
    assertEquals(0, accountsService.balance(merchant, account.publicId())
        .compareTo(Money.ofBrl("0.0000")));
  }

  @Test
  void requestValidatesTtlShapeAndAccountState() {
    var account = fundedAccount("100.0000");
    assertThrows(IllegalArgumentException.class, () -> payouts.create(
        SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl("5.0000"), "bank.ttl",
            Duration.ofSeconds(59))));
    assertThrows(IllegalArgumentException.class, () -> payouts.create(
        SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl("5.0000"), "bank.ttl",
            Duration.ofSeconds(86401))));

    accountsService.freeze(SeedMerchant.PUBLIC_ID, account.publicId());
    assertThrows(PaymentAccountNotActiveException.class, () -> payouts.create(
        SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl("5.0000"), "bank.ttl", null)));

    assertThrows(UnknownPaymentAccountException.class, () -> payouts.create(
        SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(UUID.randomUUID(), Money.ofBrl("5.0000"), "bank.ttl", null)));
  }

  /**
   * The reservation fence itself: {@code create} must block on the merchant's
   * ledger-account row lock. Deterministic — an admin connection holds the
   * FOR UPDATE the request path waits on, so no sleep-based race: the request
   * parks while the lock is held and completes the moment it is released.
   */
  @Test
  void createWaitsOnTheLedgerAccountRowLock() throws Exception {
    var account = fundedAccount("100.0000");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (var c = adminConnection()) {
      c.setAutoCommit(false);
      try (var st = c.prepareStatement(
          "select 1 from ledger.ledger_account where public_id = ? for update")) {
        st.setObject(1, account.ledgerAccountPublicId());
        try (var rs = st.executeQuery()) {
          assertTrue(rs.next());
        }
        Future<Payout> future = pool.submit(() -> payouts.create(SeedMerchant.PUBLIC_ID,
            new CreatePayoutCommand(account.publicId(), Money.ofBrl("10.0000"), "bank.lock-pin", null)));
        assertThrows(TimeoutException.class, () -> future.get(2, TimeUnit.SECONDS),
            "create must block while the ledger-account row lock is held");
        assertFalse(future.isDone());
        c.commit();
        var payout = register(future.get(30, TimeUnit.SECONDS));
        assertEquals(PayoutStatus.REQUESTED, payout.status());
        assertEquals(PayoutStatus.REQUESTED,
            payoutRows.findByPublicId(payout.publicId()).orElseThrow().status());
        assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId())
            .compareTo(Money.ofBrl("90.0000")));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static void assertLeg(List<PostedPosting> postings, UUID accountPublicId,
      Direction direction, String amount) {
    var matches = postings.stream()
        .filter(p -> accountPublicId.equals(p.accountPublicId()) && p.direction() == direction)
        .toList();
    assertEquals(1, matches.size(), postings.toString());
    assertEquals(0, matches.get(0).amount().compareTo(Money.ofBrl(amount)), postings.toString());
  }

  /**
   * The container is shared across classes and the conciliation suites assert
   * over now-relative windows. Push this class's settlements, network charges,
   * and payout fixtures two hours back — the same DB-side rewrite the sibling
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
