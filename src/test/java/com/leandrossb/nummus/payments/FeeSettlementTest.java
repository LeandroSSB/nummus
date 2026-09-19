package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class FeeSettlementTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private SimulatorService simulator;
  @Autowired
  private MerchantsService merchants;
  @Autowired
  private ApiKeysService keys;

  private record Fixture(String auth, String merchantId, String intentId) {}

  /**
   * A merchant carrying the given schedule, its API key, a payment account, and
   * an intent whose charge is already paid — the first GET settles it.
   */
  private Fixture paidIntent(FeeSchedule fee) throws Exception {
    var merchant = merchants.create("fee settle " + UUID.randomUUID(), fee);
    var key = keys.create(merchant.publicId());
    String auth = "Bearer " + key.secret();
    var account = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"fee settle account\"}"))
        .andExpect(status().isCreated()).andReturn();
    String accountId = JsonPath.read(account.getResponse().getContentAsString(), "$.publicId");
    var intent = mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":100.00}"))
        .andExpect(status().isCreated()).andReturn();
    String intentId = JsonPath.read(intent.getResponse().getContentAsString(), "$.publicId");
    String chargeId = JsonPath.read(intent.getResponse().getContentAsString(), "$.chargeId");
    simulator.pay(UUID.fromString(chargeId));
    return new Fixture(auth, merchant.publicId().toString(), intentId);
  }

  @Test
  void settlesThreeLegsWithFeeCreditedToRevenue() throws Exception {
    var f = paidIntent(new FeeSchedule(new BigDecimal("0.0099"), new BigDecimal("0.39")));
    mockMvc.perform(get("/v1/payment-intents/" + f.intentId()).header("Authorization", f.auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    try (var c = adminConnection(); var st = c.createStatement()) {
      var rs = st.executeQuery("""
          select p.direction, a.type, p.amount
          from ledger.journal_posting p
          join ledger.ledger_account a on a.id = p.account_id
          join ledger.journal_transaction t on t.id = p.transaction_id
          where t.public_id = (select journal_transaction_public_id
                               from payments.payment_intent
                               where public_id = '%s')""".formatted(f.intentId()));
      record Leg(String direction, String type, BigDecimal amount) {}
      List<Leg> legs = new ArrayList<>();
      while (rs.next()) {
        legs.add(new Leg(rs.getString("direction"), rs.getString("type"), rs.getBigDecimal("amount")));
      }
      assertEquals(3, legs.size());
      assertTrue(legs.contains(new Leg("DEBIT", "ASSET", new BigDecimal("100.0000"))), legs.toString());
      assertTrue(legs.contains(new Leg("CREDIT", "LIABILITY", new BigDecimal("98.6200"))), legs.toString());
      assertTrue(legs.contains(new Leg("CREDIT", "REVENUE", new BigDecimal("1.3800"))), legs.toString());

      var fact = st.executeQuery("select fee_amount from payments.payment_intent "
          + "where public_id = '" + f.intentId() + "'");
      assertTrue(fact.next());
      assertEquals(0, fact.getBigDecimal("fee_amount").compareTo(new BigDecimal("1.38")));
    }
  }

  @Test
  void zeroFeeMerchantSettlesTwoLegsWithZeroFact() throws Exception {
    var f = paidIntent(FeeSchedule.ZERO);
    mockMvc.perform(get("/v1/payment-intents/" + f.intentId()).header("Authorization", f.auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    try (var c = adminConnection(); var st = c.createStatement()) {
      var rs = st.executeQuery("select fee_amount from payments.payment_intent "
          + "where public_id = '" + f.intentId() + "'");
      assertTrue(rs.next());
      assertEquals(0, rs.getBigDecimal("fee_amount").compareTo(BigDecimal.ZERO));
    }
  }

  @Test
  void settleTimeRateWinsOverCreationTime() throws Exception {
    var f = paidIntent(FeeSchedule.ZERO);                       // quoted at zero
    merchants.updateFeeSchedule(UUID.fromString(f.merchantId()), // raised before settle
        new FeeSchedule(new BigDecimal("0.01"), BigDecimal.ZERO));
    mockMvc.perform(get("/v1/payment-intents/" + f.intentId()).header("Authorization", f.auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    try (var c = adminConnection(); var st = c.createStatement()) {
      var rs = st.executeQuery("select fee_amount from payments.payment_intent "
          + "where public_id = '" + f.intentId() + "'");
      assertTrue(rs.next());
      assertEquals(0, rs.getBigDecimal("fee_amount").compareTo(new BigDecimal("1.00")));
    }
  }

  @Test
  void settledEventCarriesFeeAndNet() throws Exception {
    var f = paidIntent(new FeeSchedule(new BigDecimal("0.0099"), new BigDecimal("0.39")));
    mockMvc.perform(get("/v1/payment-intents/" + f.intentId()).header("Authorization", f.auth()))
        .andExpect(status().isOk());
    try (var c = adminConnection(); var st = c.createStatement()) {
      var rs = st.executeQuery("select payload from webhooks.webhook_event "
          + "where type = 'payment_intent.settled' order by id desc limit 1");
      assertTrue(rs.next());
      String payload = rs.getString("payload");
      assertTrue(payload.contains("\"fee\":\"1.38\""), payload);
      assertTrue(payload.contains("\"netAmount\":\"98.62\""), payload);
    }
  }
}
