package com.leandrossb.nummus.psp_simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.payments.application.PaymentNetwork;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.psp_simulator.domain.RefundExceedsChargeException;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class RefundNetworkTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PaymentNetwork paymentNetwork;

  @Autowired
  private SimulatorService simulator;

  @Test
  void refundLifecycleMirrorsTransfers() throws Exception {
    var charge = simulator.create(Money.ofBrl("100.0000"));
    var refund = simulator.createRefund(charge.publicId(), Money.ofBrl("25.5000"));

    mockMvc.perform(get("/simulator/refunds/{id}", refund.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.amount").value(25.5000))
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andExpect(jsonPath("$.chargePublicId").value(charge.publicId().toString()));

    mockMvc.perform(post("/simulator/refunds/{id}/pay", refund.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    mockMvc.perform(post("/simulator/refunds/{id}/pay", refund.publicId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void refundFailIsTerminal() throws Exception {
    var charge = simulator.create(Money.ofBrl("10.0000"));
    var refund = simulator.createRefund(charge.publicId(), Money.ofBrl("1.0000"));

    mockMvc.perform(post("/simulator/refunds/{id}/fail", refund.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));

    mockMvc.perform(post("/simulator/refunds/{id}/pay", refund.publicId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void unknownRefundIs404() throws Exception {
    mockMvc.perform(get("/simulator/refunds/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void networkPortExposesRefunds() throws Exception {
    var charge = simulator.create(Money.ofBrl("100.0000"));
    var refund = paymentNetwork.createChargeRefund(charge.publicId(),
        Money.of(new BigDecimal("12.50"), Currency.getInstance("BRL")));

    assertEquals(ChargeStatus.PENDING, refund.status());
    assertEquals(0, refund.amount().compareTo(Money.ofBrl("12.50")));
    assertEquals(charge.publicId(), refund.chargePublicId());

    simulator.payRefund(refund.publicId());

    var fetched = paymentNetwork.getChargeRefund(refund.publicId());
    assertEquals(ChargeStatus.SUCCEEDED, fetched.status());
    assertEquals(0, fetched.amount().compareTo(Money.ofBrl("12.50")));
    assertEquals(charge.publicId(), fetched.chargePublicId());
  }

  @Test
  void networkRejectsOverRefund() throws Exception {
    var charge = simulator.create(Money.ofBrl("100.0000"));
    paymentNetwork.createChargeRefund(charge.publicId(), Money.ofBrl("60.0000"));

    // The first refund is still PENDING — it must count against the charge.
    var excess = assertThrows(RefundExceedsChargeException.class,
        () -> paymentNetwork.createChargeRefund(charge.publicId(), Money.ofBrl("50.0000")));

    assertEquals(charge.publicId(), excess.chargePublicId());
    assertEquals(0, excess.remaining().compareTo(Money.ofBrl("40.0000")));
    assertEquals(0, excess.requested().compareTo(Money.ofBrl("50.0000")));
  }
}
