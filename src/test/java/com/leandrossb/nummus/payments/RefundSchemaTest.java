package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void refundRowIsInsertOnlyWithDefaults() throws Exception {
    String refundId = UUID.randomUUID().toString();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into payments.refund "
          + "(public_id, intent_public_id, amount, network_refund_public_id, "
          + "expires_at, hold_transaction_public_id) values ('" + refundId + "', '"
          + UUID.randomUUID() + "', 10.0000, '" + UUID.randomUUID()
          + "', now() + interval '30 minutes', '" + UUID.randomUUID() + "')"));
      try (ResultSet rs = st.executeQuery("select status, settled_at, "
          + "created_at is not null as stamped from payments.refund "
          + "where public_id = '" + refundId + "'")) {
        assertTrue(rs.next());
        assertEquals("REQUESTED", rs.getString("status"));
        assertNull(rs.getObject("settled_at"));
        assertTrue(rs.getBoolean("stamped"));
      }
      assertEquals(1, st.executeUpdate("update payments.refund set status = 'SETTLED', "
          + "settled_at = now(), execute_transaction_public_id = '"
          + UUID.randomUUID() + "' where public_id = '" + refundId + "'"));
      assertEquals(1, st.executeUpdate("update payments.refund set status = 'EXPIRED', "
          + "return_transaction_public_id = '" + UUID.randomUUID()
          + "' where public_id = '" + refundId + "'"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("update payments.refund set amount = 99.0000 "
              + "where public_id = '" + refundId + "'"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("delete from payments.refund where public_id = '" + refundId + "'"));
    }
  }

  @Test
  void networkRefundRowMirrorsTransferChecks() throws Exception {
    String chargeId = UUID.randomUUID().toString();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into psp_simulator.charge_refund "
          + "(charge_public_id, amount) values ('" + chargeId + "', 10.0000)"));
      try (ResultSet rs = st.executeQuery("select status, created_at is not null as created, "
          + "updated_at is not null as updated from psp_simulator.charge_refund "
          + "where charge_public_id = '" + chargeId + "'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString("status"));
        assertTrue(rs.getBoolean("created"));
        assertTrue(rs.getBoolean("updated"));
      }
      assertEquals(1, st.executeUpdate("update psp_simulator.charge_refund "
          + "set status = 'SUCCEEDED', updated_at = now() "
          + "where charge_public_id = '" + chargeId + "'"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("update psp_simulator.charge_refund set amount = 99.0000 "
              + "where charge_public_id = '" + chargeId + "'"));
    }
  }

  @Test
  void refundReserveAccountExists() throws Exception {
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("select type from ledger.ledger_account "
          + "where public_id = '5f9c3b2e-0000-4000-8000-000000000004'")) {
        assertTrue(rs.next());
        assertEquals("LIABILITY", rs.getString("type"));
      }
    }
  }
}
