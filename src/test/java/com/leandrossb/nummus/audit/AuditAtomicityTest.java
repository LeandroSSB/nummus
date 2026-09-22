package com.leandrossb.nummus.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The atomicity pin: an audit entry commits with its action or not at all.
 * A fault planted on the audit table itself — a check (false) constraint,
 * NOT VALID so existing rows stand while every new insert fails — must doom
 * the whole action: the mint request fails, no key row survives, and no
 * orphaned entry records a mint that never happened. Recording runs
 * MANDATORY inside the action's transaction, so shared fate is the mechanism
 * under pin; any regression that disconnects the two — a swallowed record
 * failure, a split transaction, a write-behind recorder — breaks an
 * assertion below.
 */
@AutoConfigureMockMvc
class AuditAtomicityTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";
  private static final String PROBE = "audit_probe_chk";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Test
  void aFailedActionRollsBackItsEntry() throws Exception {
    // Fixture with a null actor key: it records no entry of its own.
    var caller = operatorKeys.create("atomicity-actor", null, null);
    int mintedBefore = mintedEntryCount();
    int keysBefore = operatorKeyCount();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("alter table audit.operator_action add constraint "
          + PROBE + " check (false) not valid");
      try {
        mockMvc.perform(post("/v1/operator/api-keys")
                .header("Authorization", "Bearer " + caller.secret())
                .header(KEY, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"atomicity-probe\"}"))
            .andExpect(status().is5xxServerError());
        // MockMvc rendered the failure as a 5xx; the rollback claims are
        // settled by the count assertions below.
      } catch (Exception escaped) {
        // The unhandled path: no exception resolver owns a check-constraint
        // violation, so perform() rethrows it. It must carry the planted
        // probe — an incidental failure would pass the rollback assertions
        // vacuously.
        Assertions.assertTrue(mentions(escaped, PROBE),
            "the mint must fail on the planted probe, got: " + escaped);
      } finally {
        st.executeUpdate("alter table audit.operator_action drop constraint " + PROBE);
      }
    }
    Assertions.assertEquals(mintedBefore, mintedEntryCount(),
        "no orphaned minted entry may survive the failed action");
    Assertions.assertEquals(keysBefore, operatorKeyCount(),
        "a mint whose audit entry failed must not commit its key either");
  }

  private int mintedEntryCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(
            "select count(*) from audit.operator_action where action = 'operator_key.minted'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private int operatorKeyCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from merchants.operator_key")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static boolean mentions(Throwable t, String needle) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (String.valueOf(cur.getMessage()).contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
