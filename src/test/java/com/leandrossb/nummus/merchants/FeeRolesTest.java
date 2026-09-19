package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeeRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleReadsAndUpdatesTheFeeColumnsAndIntentFeeFact() throws Exception {
    String merchantId = UUID.randomUUID().toString();
    String intentId = UUID.randomUUID().toString();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.merchant (public_id, name) values ('"
          + merchantId + "', 'fee roles probe')");
      assertEquals(1, st.executeUpdate("update merchants.merchant set fee_rate = 0.0099, "
          + "fee_fixed = 0.39 where public_id = '" + merchantId + "'"));
      st.executeUpdate("insert into payments.payment_intent (public_id, account_public_id, amount, "
          + "status, charge_public_id, expires_at) values ('" + intentId + "', '"
          + UUID.randomUUID() + "', 10.00, 'SETTLED', '" + UUID.randomUUID() + "', now())");
      assertEquals(1, st.executeUpdate("update payments.payment_intent set fee_amount = 0.10 "
          + "where public_id = '" + intentId + "'"));
    }
  }
}
