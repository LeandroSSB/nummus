package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class OperatorKeysSchemaTest extends IntegrationTestBase {

  @Test
  void operatorKeyHashIsGloballyUniqueAndStatusChecked() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO merchants.operator_key (key_hash, prefix) VALUES ('hash-op-1', 'nummus_s')");
      SQLException duplicate = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO merchants.operator_key (key_hash, prefix) VALUES ('hash-op-1', 'nummus_s')"));
      assertEquals("23505", duplicate.getSQLState());
      SQLException badStatus = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO merchants.operator_key (key_hash, prefix, status) VALUES ('hash-op-2', 'nummus_s', 'GONE')"));
      assertEquals("23514", badStatus.getSQLState());
    }
  }
}
