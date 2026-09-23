package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConciliationRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleInsertsAndReadsButCannotUpdateReports() throws Exception {
    String reportId = UUID.randomUUID().toString();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO conciliation.settlement_report (public_id, period_from, period_to,"
          + " status, matched_count, amount_mismatched_count, missing_internal_count, missing_external_count)"
          + " VALUES ('" + reportId + "', now(), now() + interval '1 hour', 'OPEN', 1, 0, 0, 0)");
      long rowId;
      try (ResultSet rs = st.executeQuery(
          "SELECT id FROM conciliation.settlement_report WHERE public_id = '" + reportId + "'")) {
        rs.next();
        rowId = rs.getLong(1);
      }
      st.executeUpdate("INSERT INTO conciliation.report_line (report_id, origin, subject_type,"
          + " subject_public_id, reported_amount, match_status) VALUES (" + rowId + ", 'INTERNAL',"
          + " 'CHARGE', '" + UUID.randomUUID() + "', null, 'MISSING_EXTERNAL')");
      try (ResultSet rs = st.executeQuery(
          "SELECT count(*) FROM conciliation.report_line WHERE report_id = " + rowId)) {
        rs.next();
        assertEquals(1, rs.getInt(1));
      }
      // Write-once is enforced by privilege, not discipline.
      SQLException denied = assertThrows(SQLException.class, () -> st.executeUpdate(
          "UPDATE conciliation.settlement_report SET status = 'CONCILED' WHERE id = " + rowId));
      assertEquals("42501", denied.getSQLState());
    }
  }
}
