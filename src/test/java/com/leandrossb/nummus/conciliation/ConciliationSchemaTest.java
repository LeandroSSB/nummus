package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConciliationSchemaTest extends IntegrationTestBase {

  @Test
  void schemaEnforcesChecksAndLineUniqueness() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      SQLException badStatus = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO conciliation.settlement_report (period_from, period_to, status, matched_count,"
              + " amount_mismatched_count, missing_internal_count, missing_external_count)"
              + " VALUES (now(), now() + interval '1 hour', 'WEIRD', 0, 0, 0, 0)"));
      assertEquals("23514", badStatus.getSQLState());

      String reportId = UUID.randomUUID().toString();
      st.executeUpdate("INSERT INTO conciliation.settlement_report (public_id, period_from, period_to, status,"
          + " matched_count, amount_mismatched_count, missing_internal_count, missing_external_count)"
          + " VALUES ('" + reportId + "', now(), now() + interval '1 hour', 'OPEN', 1, 2, 3, 4)");
      long rowId;
      try (ResultSet rs = st.executeQuery(
          "SELECT id FROM conciliation.settlement_report WHERE public_id = '" + reportId + "'")) {
        rs.next();
        rowId = rs.getLong(1);
      }
      String charge = UUID.randomUUID().toString();
      st.executeUpdate("INSERT INTO conciliation.report_line (report_id, origin, charge_public_id,"
          + " reported_amount, match_status) VALUES (" + rowId + ", 'EXTERNAL', '" + charge + "', 10.0000, 'MATCHED')");
      SQLException badOrigin = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO conciliation.report_line (report_id, origin, charge_public_id, match_status)"
              + " VALUES (" + rowId + ", 'OTHER', '" + UUID.randomUUID() + "', 'MATCHED')"));
      assertEquals("23514", badOrigin.getSQLState());
      SQLException duplicateCharge = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO conciliation.report_line (report_id, origin, charge_public_id,"
              + " reported_amount, match_status) VALUES (" + rowId + ", 'EXTERNAL', '" + charge + "', 9.0000, 'AMOUNT_MISMATCH')"));
      assertEquals("23505", duplicateCharge.getSQLState());
      try (ResultSet rs = st.executeQuery(
          "SELECT internal_intent_public_id, internal_amount FROM conciliation.report_line"
              + " WHERE report_id = " + rowId)) {
        assertTrue(rs.next());
        assertTrue(rs.getObject(1) == null);
        assertTrue(rs.getObject(2) == null);
      }
    }
  }
}
