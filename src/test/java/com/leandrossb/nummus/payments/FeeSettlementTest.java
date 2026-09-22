package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.FeeRevenueAccount;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Statement;
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
  @Autowired
  private OperatorKeysService operatorKeys;

  private record Fixture(String auth, String merchantId, String intentId, String accountId,
      String chargeId) {}

  private record Leg(String direction, String type, BigDecimal amount) {}

  /**
   * A merchant carrying the given schedule, its API key, a payment account, and
   * an intent for the given amount whose charge is still PENDING — the first
   * GET polls the network and moves it.
   */
  private Fixture newIntent(FeeSchedule fee, String amount) throws Exception {
    // Creation is audited against the acting operator key: mint a probe.
    UUID actingKey = operatorKeys.create("fee-create-probe", null, null).key().publicId();
    var merchant = merchants.create("fee settle " + UUID.randomUUID(), fee, actingKey);
    var key = keys.create(merchant.publicId(), null);
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
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":" + amount + "}"))
        .andExpect(status().isCreated()).andReturn();
    String intentId = JsonPath.read(intent.getResponse().getContentAsString(), "$.publicId");
    String chargeId = JsonPath.read(intent.getResponse().getContentAsString(), "$.chargeId");
    return new Fixture(auth, merchant.publicId().toString(), intentId, accountId, chargeId);
  }

  /** A paid charge on top of {@link #newIntent}: the first GET settles it. */
  private Fixture paidIntent(FeeSchedule fee, String amount) throws Exception {
    var f = newIntent(fee, amount);
    simulator.pay(UUID.fromString(f.chargeId()));
    return f;
  }

  private Fixture paidIntent(FeeSchedule fee) throws Exception {
    return paidIntent(fee, "100.00");
  }

  /** The journal legs of the intent's settlement transaction, as posted. */
  private static List<Leg> journalLegs(Statement st, String intentId) throws SQLException {
    var rs = st.executeQuery("""
        select p.direction, a.type, p.amount
        from ledger.journal_posting p
        join ledger.ledger_account a on a.id = p.account_id
        join ledger.journal_transaction t on t.id = p.transaction_id
        where t.public_id = (select journal_transaction_public_id
                             from payments.payment_intent
                             where public_id = '%s')""".formatted(intentId));
    List<Leg> legs = new ArrayList<>();
    while (rs.next()) {
      legs.add(new Leg(rs.getString("direction"), rs.getString("type"), rs.getBigDecimal("amount")));
    }
    return legs;
  }

  @Test
  void settlesThreeLegsWithFeeCreditedToRevenue() throws Exception {
    var f = paidIntent(new FeeSchedule(new BigDecimal("0.0099"), new BigDecimal("0.39"), BigDecimal.ZERO));
    mockMvc.perform(get("/v1/payment-intents/" + f.intentId()).header("Authorization", f.auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    // The merchant sees the net credited: derived balance in natural sign.
    var balance = mockMvc.perform(get("/v1/accounts/" + f.accountId() + "/balance")
            .header("Authorization", f.auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andReturn();
    assertEquals(0, new BigDecimal(
        JsonPath.read(balance.getResponse().getContentAsString(), "$.amount").toString())
        .compareTo(new BigDecimal("98.62")));
    try (var c = adminConnection(); var st = c.createStatement()) {
      List<Leg> legs = journalLegs(st, f.intentId());
      assertEquals(3, legs.size(), legs.toString());
      assertTrue(legs.contains(new Leg("DEBIT", "ASSET", new BigDecimal("100.0000"))), legs.toString());
      assertTrue(legs.contains(new Leg("CREDIT", "LIABILITY", new BigDecimal("98.6200"))), legs.toString());
      assertTrue(legs.contains(new Leg("CREDIT", "REVENUE", new BigDecimal("1.3800"))), legs.toString());

      var fact = st.executeQuery("select fee_amount from payments.payment_intent "
          + "where public_id = '" + f.intentId() + "'");
      assertTrue(fact.next());
      assertEquals(0, fact.getBigDecimal("fee_amount").compareTo(new BigDecimal("1.38")));

      // The system revenue account's derived balance from this settlement.
      var revenue = st.executeQuery("""
          select coalesce(sum(p.amount), 0)
          from ledger.journal_posting p
          join ledger.ledger_account a on a.id = p.account_id
          join ledger.journal_transaction t on t.id = p.transaction_id
          where a.public_id = '%s'
            and t.public_id = (select journal_transaction_public_id
                               from payments.payment_intent
                               where public_id = '%s')"""
          .formatted(FeeRevenueAccount.PUBLIC_ID, f.intentId()));
      assertTrue(revenue.next());
      assertEquals(0, revenue.getBigDecimal(1).compareTo(new BigDecimal("1.38")));
    }
  }

  @Test
  void zeroFeeMerchantSettlesTwoLegsWithZeroFact() throws Exception {
    var f = paidIntent(FeeSchedule.ZERO);
    mockMvc.perform(get("/v1/payment-intents/" + f.intentId()).header("Authorization", f.auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    try (var c = adminConnection(); var st = c.createStatement()) {
      List<Leg> legs = journalLegs(st, f.intentId());
      assertEquals(2, legs.size(), legs.toString());
      assertTrue(legs.contains(new Leg("DEBIT", "ASSET", new BigDecimal("100.0000"))), legs.toString());
      assertTrue(legs.contains(new Leg("CREDIT", "LIABILITY", new BigDecimal("100.0000"))),
          legs.toString());

      var rs = st.executeQuery("select fee_amount from payments.payment_intent "
          + "where public_id = '" + f.intentId() + "'");
      assertTrue(rs.next());
      assertEquals(0, rs.getBigDecimal("fee_amount").compareTo(BigDecimal.ZERO));
    }
  }

  @Test
  void cappedFeeSettlesTwoLegsWithEntireGrossAsFee() throws Exception {
    // The fixed component alone (0.39) exceeds the 0.10 gross: the cap binds,
    // net is zero, and the merchant leg is omitted rather than posted at 0.00.
    var f = paidIntent(new FeeSchedule(BigDecimal.ZERO, new BigDecimal("0.39"), BigDecimal.ZERO), "0.10");
    mockMvc.perform(get("/v1/payment-intents/" + f.intentId()).header("Authorization", f.auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    try (var c = adminConnection(); var st = c.createStatement()) {
      List<Leg> legs = journalLegs(st, f.intentId());
      assertEquals(2, legs.size(), legs.toString());
      assertTrue(legs.contains(new Leg("DEBIT", "ASSET", new BigDecimal("0.1000"))), legs.toString());
      assertTrue(legs.contains(new Leg("CREDIT", "REVENUE", new BigDecimal("0.1000"))), legs.toString());

      var fact = st.executeQuery("select fee_amount from payments.payment_intent "
          + "where public_id = '" + f.intentId() + "'");
      assertTrue(fact.next());
      assertEquals(0, fact.getBigDecimal("fee_amount").compareTo(new BigDecimal("0.10")));
    }
  }

  @Test
  void settleTimeRateWinsOverCreationTime() throws Exception {
    var f = paidIntent(FeeSchedule.ZERO);                       // quoted at zero
    merchants.updateFeeSchedule(UUID.fromString(f.merchantId()), // raised before settle
        new FeeSchedule(new BigDecimal("0.01"), BigDecimal.ZERO, BigDecimal.ZERO),
        operatorKeys.create("fee-settle-probe", null, null).key().publicId());
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
    var f = paidIntent(new FeeSchedule(new BigDecimal("0.0099"), new BigDecimal("0.39"), BigDecimal.ZERO));
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

  @Test
  void failedEventPayloadCarriesNoFeeFields() throws Exception {
    var f = newIntent(FeeSchedule.ZERO, "100.00");
    simulator.fail(UUID.fromString(f.chargeId()));
    mockMvc.perform(get("/v1/payment-intents/" + f.intentId()).header("Authorization", f.auth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
    try (var c = adminConnection(); var st = c.createStatement()) {
      var rs = st.executeQuery("select payload from webhooks.webhook_event "
          + "where type = 'payment_intent.failed' order by id desc limit 1");
      assertTrue(rs.next());
      String payload = rs.getString("payload");
      assertFalse(payload.contains("\"fee\""), payload);
      assertFalse(payload.contains("\"netAmount\""), payload);
    }
  }
}
