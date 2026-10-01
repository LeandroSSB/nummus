package com.leandrossb.nummus.psp_simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** A pending charge can be withdrawn on the network; a terminal one cannot. */
@AutoConfigureMockMvc
class ChargeCancelRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private SimulatorService simulator;

  @Test
  void cancelWithdrawsAPendingCharge() throws Exception {
    var charge = simulator.create(com.leandrossb.nummus.ledger.domain.Money.ofBrl("10.0000"));
    mockMvc.perform(post("/simulator/charges/{id}/cancel", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    mockMvc.perform(get("/simulator/charges/{id}", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void cancelRejectsATerminalCharge() throws Exception {
    var charge = simulator.create(com.leandrossb.nummus.ledger.domain.Money.ofBrl("10.0000"));
    simulator.pay(charge.publicId());
    mockMvc.perform(post("/simulator/charges/{id}/cancel", charge.publicId()))
        .andExpect(status().isConflict());
  }
}
