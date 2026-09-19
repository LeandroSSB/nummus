package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentsSettlementQueryTest extends IntegrationTestBase {

  @Autowired
  private PaymentsService payments;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  private UUID settle(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("Settle Query Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    return intent.publicId();
  }

  @Test
  void listSettlementsHonorsHalfOpenWindowAndSettledOnly() throws Exception {
    var settled = settle("6.0000");
    // A settled intent outside the window (settled in the future-proof way:
    // settle one now, then query a window that ended before now).
    Instant past = Instant.now().minusSeconds(3600);

    var none = payments.listSettlements(past.minusSeconds(60), past);
    assertEquals(0, none.size());

    var all = payments.listSettlements(past, Instant.now().plusSeconds(60));
    // Membership, not a global size pin: the shared container carries other
    // classes' settlements inside any now-window, and method order is not
    // specified. The pre-fixture past window (above) stays empty regardless.
    assertTrue(all.stream().anyMatch(v -> v.intentPublicId().equals(settled)));
    var view = all.stream().filter(v -> v.intentPublicId().equals(settled))
        .findFirst().orElseThrow();
    assertEquals(Money.ofBrl("6.0000").amount(), view.amount().amount());
    assertTrue(view.journalTransactionPublicId() != null);
    assertTrue(view.settledAt() != null);
  }
}
