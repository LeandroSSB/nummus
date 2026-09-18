package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class IdempotencySchemaTest extends IntegrationTestBase {

  @Test
  void schemaAcceptsRowsAndEnforcesKeyUniqueness() throws Exception {
    String key = "schema-" + java.util.UUID.randomUUID();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("""
          INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at)
          VALUES ('%s', decode('00', 'hex'), now() + interval '24 hours')
          """.formatted(key));
      try (ResultSet rs = st.executeQuery(
          "SELECT response_status, response_body, created_at FROM idempotency.idempotency_keys WHERE key = '" + key + "'")) {
        assertTrue(rs.next());
        assertTrue(rs.getObject(1) == null);
        assertTrue(rs.getObject(2) == null);
        assertTrue(rs.getObject(3) != null);
      }
      SQLException duplicate = assertThrows(SQLException.class, () -> st.executeUpdate("""
          INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at)
          VALUES ('%s', decode('01', 'hex'), now() + interval '24 hours')
          """.formatted(key)));
      assertEquals("23505", duplicate.getSQLState());
    }
  }
}
