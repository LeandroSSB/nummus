package com.leandrossb.nummus.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class AuditSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  private String seedOperatorKey() throws Exception {
    String hash = "audit-probe-" + UUID.randomUUID();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.operator_key (key_hash, prefix, label) "
          + "values ('" + hash + "', 'nummus_s', 'audit-probe')");
      try (ResultSet rs = st.executeQuery("select public_id from merchants.operator_key "
          + "where key_hash = '" + hash + "'")) {
        rs.next();
        return rs.getObject(1, UUID.class).toString();
      }
    }
  }

  @Test
  void operatorActionIsInsertOnlyWithDefaults() throws Exception {
    String actor = seedOperatorKey();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into audit.operator_action "
          + "(actor_key, action, subject_type, subject_id) values ('" + actor + "', "
          + "'probe.action', 'probe', '" + UUID.randomUUID() + "')"));
      try (ResultSet rs = st.executeQuery("select detail, occurred_at is not null as stamped "
          + "from audit.operator_action where action = 'probe.action'")) {
        assertTrue(rs.next());
        assertEquals("{}", rs.getString("detail"));
        assertTrue(rs.getBoolean("stamped"));
      }
    }
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into audit.operator_action "
          + "(actor_key, action, subject_type) values ('" + actor + "', 'probe.app', 'probe')"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("update audit.operator_action set action = 'tampered' "
              + "where action = 'probe.app'"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("delete from audit.operator_action where action = 'probe.app'"));
    }
  }

  @Test
  void actorKeyIsAHardForeignKey() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("insert into audit.operator_action "
              + "(actor_key, action, subject_type) values ('"
              + UUID.randomUUID() + "', 'probe.ghost', 'probe')"));
    }
  }

  @Test
  void subjectIdStaysNullable() throws Exception {
    String actor = seedOperatorKey();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into audit.operator_action "
          + "(actor_key, action, subject_type) values ('" + actor + "', 'probe.nosubject', 'probe')"));
      try (ResultSet rs = st.executeQuery("select subject_id is null as no_subject "
          + "from audit.operator_action where action = 'probe.nosubject'")) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("no_subject"));
      }
    }
  }
}
