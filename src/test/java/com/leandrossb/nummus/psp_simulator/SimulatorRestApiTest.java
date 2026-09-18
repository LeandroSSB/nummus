package com.leandrossb.nummus.psp_simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentNetwork;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
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
}
