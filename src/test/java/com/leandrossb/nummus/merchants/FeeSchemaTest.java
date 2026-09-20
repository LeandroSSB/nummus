package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class FeeSchemaTest extends IntegrationTestBase {

  private static final String PROBE = "22222222-2222-4222-8222-222222222221";

  @Test
  void feeColumnsDefaultToZeroAreCheckedAndRevenueAccountIsSeeded() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate(
          "insert into merchants.merchant (public_id, name) values ('" + PROBE + "', 'fee schema probe')");
      ResultSet rs = st.executeQuery(
          "select fee_rate, fee_fixed from merchants.merchant where public_id = '" + PROBE + "'");
      assertTrue(rs.next());
      assertEquals(0, rs.getBigDecimal("fee_rate").compareTo(BigDecimal.ZERO));
      assertEquals(0, rs.getBigDecimal("fee_fixed").compareTo(BigDecimal.ZERO));

      SQLException badRate = assertThrows(SQLException.class, () -> st.executeUpdate(
          "update merchants.merchant set fee_rate = 1 where public_id = '" + PROBE + "'"));
      assertEquals("23514", badRate.getSQLState());
      SQLException negativeRate = assertThrows(SQLException.class, () -> st.executeUpdate(
          "update merchants.merchant set fee_rate = -0.1 where public_id = '" + PROBE + "'"));
      assertEquals("23514", negativeRate.getSQLState());
      SQLException negativeFixed = assertThrows(SQLException.class, () -> st.executeUpdate(
          "update merchants.merchant set fee_fixed = -0.01 where public_id = '" + PROBE + "'"));
      assertEquals("23514", negativeFixed.getSQLState());

      ResultSet revenue = st.executeQuery(
          "select type from ledger.ledger_account where public_id = '5f9c3b2e-0000-4000-8000-000000000002'");
      assertTrue(revenue.next());
      assertEquals("REVENUE", revenue.getString("type"));
    }
  }
}
