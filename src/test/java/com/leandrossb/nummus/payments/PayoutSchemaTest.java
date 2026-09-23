package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayoutSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void payoutRowIsInsertOnlyWithDefaults() throws Exception {
    String payoutId = UUID.randomUUID().toString();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into payments.payout "
          + "(public_id, account_public_id, amount, destination_bank_key, transfer_public_id, "
          + "expires_at, request_transaction_public_id) values ('" + payoutId + "', '"
          + UUID.randomUUID() + "', 10.0000, 'probe-bank', '" + UUID.randomUUID()
          + "', now() + interval '30 minutes', '" + UUID.randomUUID() + "')"));
      try (ResultSet rs = st.executeQuery("select status, fee_amount, settled_at, "
          + "bank_account_public_id, created_at is not null as stamped from payments.payout "
          + "where public_id = '" + payoutId + "'")) {
        assertTrue(rs.next());
        assertEquals("REQUESTED", rs.getString("status"));
        assertNull(rs.getBigDecimal("fee_amount"));
        assertNull(rs.getObject("settled_at"));
        // The registry reference is nullable by design: pre-M20 rows and raw
        // probes keep a null reference.
        assertNull(rs.getObject("bank_account_public_id"));
        assertTrue(rs.getBoolean("stamped"));
      }
      assertEquals(1, st.executeUpdate("update payments.payout set status = 'SETTLED', "
          + "settled_at = now(), fee_amount = 0.5000, execute_transaction_public_id = '"
          + UUID.randomUUID() + "' where public_id = '" + payoutId + "'"));
      assertEquals(1, st.executeUpdate("update payments.payout set status = 'EXPIRED', "
          + "return_transaction_public_id = '" + UUID.randomUUID()
          + "' where public_id = '" + payoutId + "'"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("update payments.payout set amount = 99.0000 "
              + "where public_id = '" + payoutId + "'"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("delete from payments.payout where public_id = '" + payoutId + "'"));
    }
  }

  @Test
  void transferRowMirrorsChargeChecks() throws Exception {
    String bankKey = "payout-probe-" + UUID.randomUUID();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into psp_simulator.payout_transfer "
          + "(amount, destination_bank_key) values (10.0000, '" + bankKey + "')"));
      try (ResultSet rs = st.executeQuery("select status, created_at is not null as created, "
          + "updated_at is not null as updated from psp_simulator.payout_transfer "
          + "where destination_bank_key = '" + bankKey + "'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString("status"));
        assertTrue(rs.getBoolean("created"));
        assertTrue(rs.getBoolean("updated"));
      }
      assertEquals(1, st.executeUpdate("update psp_simulator.payout_transfer "
          + "set status = 'SUCCEEDED', updated_at = now() "
          + "where destination_bank_key = '" + bankKey + "'"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("update psp_simulator.payout_transfer set amount = 99.0000 "
              + "where destination_bank_key = '" + bankKey + "'"));
    }
  }

  @Test
  void payoutFeeColumnsDefaultToZeroAndReserveAccountExists() throws Exception {
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery(
          "select payout_fee_fixed from merchants.merchant limit 1")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getBigDecimal("payout_fee_fixed").compareTo(BigDecimal.ZERO));
      }
      try (ResultSet rs = st.executeQuery(
          "select payout_fixed from merchants.fee_schedule_entry limit 1")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getBigDecimal("payout_fixed").compareTo(BigDecimal.ZERO));
      }
      try (ResultSet rs = st.executeQuery("select type from ledger.ledger_account "
          + "where public_id = '5f9c3b2e-0000-4000-8000-000000000003'")) {
        assertTrue(rs.next());
        assertEquals("LIABILITY", rs.getString("type"));
      }
    }
  }
}
