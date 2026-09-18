package com.leandrossb.nummus.psp_simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulatorSchemaTest extends IntegrationTestBase {

  @Test
  void schemaAcceptsChargeRowsWithDefaults() throws Exception {
    String chargeId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO psp_simulator.charge (public_id, amount) "
          + "VALUES ('" + chargeId + "', 25.5000)");
      try (ResultSet rs = st.executeQuery(
          "SELECT status FROM psp_simulator.charge WHERE public_id = '" + chargeId + "'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString(1));
      }
    }
  }
}
