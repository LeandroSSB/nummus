package com.leandrossb.nummus.psp_simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentNetwork;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SimulatorRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PaymentNetwork paymentNetwork;

  @Autowired
  private SimulatorService simulator;

  @Test
  void payerActionsTransitionPendingChargeAndSecondActionConflicts() throws Exception {
    var charge = paymentNetwork.createCharge(Money.ofBrl("25.5000"));

    mockMvc.perform(post("/simulator/charges/{id}/pay", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.amount").value(25.5000))
        .andExpect(jsonPath("$.currency").value("BRL"));

    mockMvc.perform(get("/simulator/charges/{id}", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    mockMvc.perform(post("/simulator/charges/{id}/fail", charge.publicId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void failActionTransitionsPendingCharge() throws Exception {
    var charge = paymentNetwork.createCharge(Money.ofBrl("1.0000"));
    mockMvc.perform(post("/simulator/charges/{id}/fail", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
  }

  @Test
  void unknownChargeReturns404() throws Exception {
    mockMvc.perform(get("/simulator/charges/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void networkPortEchoesAmountOnGetCharge() throws Exception {
    var charge = paymentNetwork.createCharge(Money.ofBrl("42.0000"));
    var fetched = paymentNetwork.getCharge(charge.publicId());
    org.junit.jupiter.api.Assertions.assertEquals(0, fetched.amount().compareTo(Money.ofBrl("42.0000")));
    org.junit.jupiter.api.Assertions.assertEquals(
        com.leandrossb.nummus.payments.application.ChargeStatus.PENDING, fetched.status());
  }

  @Test
  void settlementReportReturnsSucceededChargesInsideWindow() throws Exception {
    var paid = simulator.create(Money.ofBrl("8.0000"));
    simulator.pay(paid.publicId());
    var pending = simulator.create(Money.ofBrl("7.0000"));
    var failed = simulator.create(Money.ofBrl("6.0000"));
    simulator.fail(failed.publicId());

    Instant from = Instant.now().minusSeconds(60);
    Instant to = Instant.now().plusSeconds(60);
    mockMvc.perform(get("/simulator/settlement-report")
            .param("from", from.toString()).param("to", to.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(String.format("$[?(@.subjectId == '%s')].status", pending.publicId()))
            .doesNotExist())
        .andExpect(jsonPath(String.format("$[?(@.subjectId == '%s')]", failed.publicId()))
            .doesNotExist())
        .andExpect(jsonPath(String.format(
            "$[?(@.kind == 'CHARGE' && @.subjectId == '%s')].amount", paid.publicId()))
            .value(8.0000));

    // Settled charges are timestamped now, so none of this test's charges can
    // fall in the window that ends 30 seconds ago. Asserted per charge rather
    // than as a globally empty report because other tests create charges too.
    mockMvc.perform(get("/simulator/settlement-report")
            .param("from", from.toString())
            .param("to", Instant.now().minusSeconds(30).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(String.format("$[?(@.subjectId == '%s')]", paid.publicId()))
            .doesNotExist())
        .andExpect(jsonPath(String.format("$[?(@.subjectId == '%s')]", pending.publicId()))
            .doesNotExist())
        .andExpect(jsonPath(String.format("$[?(@.subjectId == '%s')]", failed.publicId()))
            .doesNotExist());
  }

  @Test
  void settlementReportCoversExecutedMoneyOut() throws Exception {
    var transfer = simulator.createTransfer(Money.ofBrl("9.0000"), "bank-key-1");
    simulator.payTransfer(transfer.publicId());
    var pending = simulator.createTransfer(Money.ofBrl("8.0000"), "bank-key-1");
    var charge = simulator.create(Money.ofBrl("50.0000"));
    var refund = simulator.createRefund(charge.publicId(), Money.ofBrl("5.0000"));
    simulator.payRefund(refund.publicId());

    mockMvc.perform(get("/simulator/settlement-report")
            .param("from", Instant.now().minusSeconds(60).toString())
            .param("to", Instant.now().plusSeconds(60).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(String.format(
            "$[?(@.kind == 'PAYOUT_TRANSFER' && @.subjectId == '%s')].amount", transfer.publicId()))
            .value(9.0000))
        .andExpect(jsonPath(String.format("$[?(@.kind == 'PAYOUT_TRANSFER' && @.subjectId == '%s')]",
            pending.publicId())).doesNotExist())
        .andExpect(jsonPath(String.format(
            "$[?(@.kind == 'CHARGE_REFUND' && @.subjectId == '%s')].amount", refund.publicId()))
            .value(5.0000));

    // This class has no class-level sweep: push this test's executed money-out
    // rows outside every later suite's now-window — the same DB-side rewrite
    // the conciliation sweeps use. The pending rows never enter any report.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.payout_transfer SET updated_at = now() - interval '2 hours'"
          + " WHERE public_id = '" + transfer.publicId() + "'");
      st.executeUpdate("UPDATE psp_simulator.charge_refund SET updated_at = now() - interval '2 hours'"
          + " WHERE public_id = '" + refund.publicId() + "'");
    }
  }
}
