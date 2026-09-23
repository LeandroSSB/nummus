package com.leandrossb.nummus.psp_simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.payments.application.PaymentNetwork;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class TransferNetworkTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PaymentNetwork paymentNetwork;

  @Autowired
  private SimulatorService simulator;

  @Test
  void transferLifecycleMirrorsCharges() throws Exception {
    var transfer = simulator.createTransfer(Money.ofBrl("25.5000"), "bank.main-01");

    mockMvc.perform(get("/simulator/transfers/{id}", transfer.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.amount").value(25.5000))
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andExpect(jsonPath("$.destinationBankKey").value("bank.main-01"));

    mockMvc.perform(post("/simulator/transfers/{id}/pay", transfer.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    mockMvc.perform(post("/simulator/transfers/{id}/pay", transfer.publicId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void transferFailIsTerminal() throws Exception {
    var transfer = simulator.createTransfer(Money.ofBrl("1.0000"), "bank.main-01");

    mockMvc.perform(post("/simulator/transfers/{id}/fail", transfer.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));

    mockMvc.perform(post("/simulator/transfers/{id}/pay", transfer.publicId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void unknownTransferIs404() throws Exception {
    mockMvc.perform(get("/simulator/transfers/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void cancelTransitionsPendingToCancelled() throws Exception {
    var transfer = simulator.createTransfer(Money.ofBrl("10.0000"), "bank.main-01");

    mockMvc.perform(post("/simulator/transfers/{id}/cancel", transfer.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    // CANCELLED is terminal — a late pay is refused with the not-pending vocabulary.
    mockMvc.perform(post("/simulator/transfers/{id}/pay", transfer.publicId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());

    mockMvc.perform(get("/simulator/transfers/{id}", transfer.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    // The port echoes the same post-attempt state.
    var other = paymentNetwork.createPayoutTransfer(Money.ofBrl("5.0000"), "bank.main-01");
    var cancelled = paymentNetwork.cancelPayoutTransfer(other.publicId());
    assertEquals(ChargeStatus.CANCELLED, cancelled.status());
    assertEquals(other.publicId(), cancelled.publicId());
  }

  @Test
  void cancelOnTerminalIs409() throws Exception {
    var paid = simulator.createTransfer(Money.ofBrl("1.0000"), "bank.main-01");
    simulator.payTransfer(paid.publicId());
    mockMvc.perform(post("/simulator/transfers/{id}/cancel", paid.publicId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());

    var failed = simulator.createTransfer(Money.ofBrl("1.0000"), "bank.main-01");
    simulator.failTransfer(failed.publicId());
    mockMvc.perform(post("/simulator/transfers/{id}/cancel", failed.publicId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());

    mockMvc.perform(post("/simulator/transfers/{id}/cancel", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void cancelledTransfersReleaseNothingButHoldNothing() throws Exception {
    var transfer = simulator.createTransfer(Money.ofBrl("25.0000"), "bank.main-01");
    simulator.cancelTransfer(transfer.publicId());

    // Transfers carry no sum cap — cancelling is purely terminal, so a fresh
    // transfer of the same amount is neither blocked nor an over-issue.
    var retry = simulator.createTransfer(Money.ofBrl("25.0000"), "bank.main-01");
    mockMvc.perform(post("/simulator/transfers/{id}/pay", retry.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
  }

  @Test
  void networkPortExposesTransfers() throws Exception {
    var transfer = paymentNetwork.createPayoutTransfer(
        Money.of(new BigDecimal("12.50"), Currency.getInstance("BRL")), "bank.main-01");

    assertEquals(ChargeStatus.PENDING, transfer.status());
    assertEquals("bank.main-01", transfer.destinationBankKey());

    simulator.payTransfer(transfer.publicId());

    var fetched = paymentNetwork.getPayoutTransfer(transfer.publicId());
    assertEquals(ChargeStatus.SUCCEEDED, fetched.status());
  }
}
