package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MoneyOutSettlementQueryTest extends IntegrationTestBase {

  /** Fixtures this class settles or executes — backdated in the sweep so no
   *  other class's now-window ever sees them. */
  private static final List<UUID> settledIntents = new ArrayList<>();
  private static final List<UUID> networkCharges = new ArrayList<>();
  private static final List<UUID> settledPayouts = new ArrayList<>();
  private static final List<UUID> paidTransfers = new ArrayList<>();
  private static final List<UUID> settledRefunds = new ArrayList<>();
  private static final List<UUID> paidNetworkRefunds = new ArrayList<>();

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  /** Funds a fresh account with a settled 1000 charge, then pays `amount` out. */
  private Payout settlePayout(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Money-Out Query Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1000.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var payout = payouts.create(SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl(amount), "bank-key-1", null));
    simulator.payTransfer(payout.transferPublicId());
    paidTransfers.add(payout.transferPublicId());
    var settled = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());
    settledPayouts.add(settled.publicId());
    return settled;
  }

  @Test
  void payoutListSettlementsHonorsHalfOpenWindowAndSettledOnly() {
    var settled = settlePayout("30.0000");
    Instant past = Instant.now().minusSeconds(3600);

    assertEquals(0, payouts.listSettlements(past.minusSeconds(60), past).size());

    // Membership, not a global size pin: the shared container carries other
    // classes' settlements inside any now-window, and method order is not
    // specified. The pre-fixture past window (above) stays empty regardless.
    var all = payouts.listSettlements(past, Instant.now().plusSeconds(60));
    var view = all.stream().filter(v -> v.internalPublicId().equals(settled.publicId()))
        .findFirst().orElseThrow();
    assertEquals(settled.transferPublicId(), view.networkInstructionPublicId());
    assertEquals(Money.ofBrl("30.0000").amount(), view.amount().amount());
    assertTrue(view.settledAt() != null);
  }

  @Test
  void refundListSettlementsHonorsHalfOpenWindowAndSettledOnly() {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Refund Query Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("50.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var refund = refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl("10.0000"), null));
    simulator.payRefund(refund.networkRefundPublicId());
    paidNetworkRefunds.add(refund.networkRefundPublicId());
    var settled = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());
    settledRefunds.add(settled.publicId());
    Instant past = Instant.now().minusSeconds(3600);

    assertEquals(0, refunds.listSettlements(past.minusSeconds(60), past).size());

    var all = refunds.listSettlements(past, Instant.now().plusSeconds(60));
    var view = all.stream().filter(v -> v.internalPublicId().equals(settled.publicId()))
        .findFirst().orElseThrow();
    assertEquals(settled.networkRefundPublicId(), view.networkInstructionPublicId());
    assertEquals(Money.ofBrl("10.0000").amount(), view.amount().amount());
    assertTrue(view.settledAt() != null);
  }

  /** The container is shared across classes and later suites assert over
   *  now-relative windows. Push this class's fixtures two hours back — the
   *  same DB-side rewrite the conciliation divergence setups use. */
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
      if (!settledPayouts.isEmpty()) {
        st.executeUpdate("UPDATE payments.payout SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledPayouts) + ")");
      }
      if (!paidTransfers.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.payout_transfer SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(paidTransfers) + ")");
      }
      if (!settledRefunds.isEmpty()) {
        st.executeUpdate("UPDATE payments.refund SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledRefunds) + ")");
      }
      if (!paidNetworkRefunds.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge_refund SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(paidNetworkRefunds) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
