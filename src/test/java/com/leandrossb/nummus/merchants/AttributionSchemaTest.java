package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class AttributionSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void operatorKeyLabelIsNonNullWithSystemDefault() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.operator_key (key_hash, prefix) "
          + "values ('label-probe-1', 'nummus_s')");
      try (ResultSet rs = st.executeQuery("select label from merchants.operator_key "
          + "where key_hash = 'label-probe-1'")) {
        assertTrue(rs.next());
        assertEquals("system", rs.getString("label"));
      }
    }
  }

  @Test
  void feeScheduleEntryIsInsertOnlyWithNullableAttribution() throws Exception {
    String merchantId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.merchant (public_id, name) values ('"
          + merchantId + "', 'history probe')");
      assertEquals(1, st.executeUpdate("insert into merchants.fee_schedule_entry "
          + "(merchant_id, rate, fixed, created_by) select id, 0.01, 0.25, null "
          + "from merchants.merchant where public_id = '" + merchantId + "'"));
    }
    // The app role can SELECT and INSERT but holds no UPDATE or DELETE.
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("select count(*) from merchants.fee_schedule_entry "
          + "where created_by is null")) {
        assertTrue(rs.next());
      }
      st.executeUpdate("insert into merchants.fee_schedule_entry "
          + "(merchant_id, rate, fixed, created_by) select id, 0.02, 0.50, null "
          + "from merchants.merchant where public_id = '" + merchantId + "'");
      org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("update merchants.fee_schedule_entry set rate = 0.99 "
              + "where created_by is null"));
      org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("delete from merchants.fee_schedule_entry where rate = 0.02"));
    }
  }

  @Test
  void existingMerchantsCarryExactlyOneSeedEntryWithNullAttribution() throws Exception {
    // V16's backfill ran at migration time, before this row existed — a merchant
    // created after migration has no seed until its first fee PUT. So assert the
    // backfill against a PRE-EXISTING merchant instead: the seed merchant.
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from merchants.fee_schedule_entry e "
            + "join merchants.merchant m on m.id = e.merchant_id "
            + "where m.public_id = '11111111-1111-4111-8111-111111111111'")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select e.created_by, e.rate, e.fixed "
            + "from merchants.fee_schedule_entry e "
            + "join merchants.merchant m on m.id = e.merchant_id "
            + "where m.public_id = '11111111-1111-4111-8111-111111111111'")) {
      assertTrue(rs.next());
      assertEquals(null, rs.getObject("created_by"));
    }
  }
}
