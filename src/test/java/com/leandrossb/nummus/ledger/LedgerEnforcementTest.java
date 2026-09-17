package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

class LedgerEnforcementTest extends IntegrationTestBase {

  private String insertAccount(Connection c, String name) throws SQLException {
    String publicId = UUID.randomUUID().toString();
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO ledger.ledger_account (public_id, name, type) VALUES (?, ?, 'ASSET')")) {
      ps.setObject(1, UUID.fromString(publicId));
      ps.setString(2, name);
      ps.executeUpdate();
    }
    return publicId;
  }

  /** Posts (direction, amount, accountName) triples under one new transaction; commits. */
  private void insertTx(String[][] postings) throws SQLException {
    try (Connection c = adminConnection()) {
      c.setAutoCommit(false);
      String txPublicId = UUID.randomUUID().toString();
      try (Statement st = c.createStatement()) {
        st.executeUpdate("INSERT INTO ledger.journal_transaction (public_id, memo) VALUES ('"
            + txPublicId + "', 'enforcement test')");
      }
      for (String[] posting : postings) {
        try (Statement st = c.createStatement()) {
          st.executeUpdate("INSERT INTO ledger.journal_posting (transaction_id, account_id, direction, amount) "
              + "SELECT t.id, a.id, '" + posting[0] + "', " + posting[1]
              + " FROM ledger.journal_transaction t, ledger.ledger_account a "
              + "WHERE t.public_id = '" + txPublicId + "' AND a.name = '" + posting[2] + "'");
        }
      }
      c.commit();
    }
  }

  @Test
  void balancedTransactionCommits() throws Exception {
    try (Connection c = adminConnection()) {
      insertAccount(c, "bal-asset");
      insertAccount(c, "bal-liability");
    }
    assertDoesNotThrow(() -> insertTx(new String[][] {
        {"DEBIT", "10.0000", "bal-asset"},
        {"CREDIT", "10.0000", "bal-liability"}}));
  }

  @Test
  void unbalancedTransactionRejectedAtCommitEvenViaRawSql() throws Exception {
    try (Connection c = adminConnection()) {
      insertAccount(c, "unbal-asset");
      insertAccount(c, "unbal-liability");
    }
    try {
      insertTx(new String[][] {
          {"DEBIT", "10.0000", "unbal-asset"},
          {"CREDIT", "9.0000", "unbal-liability"}});
      fail("unbalanced transaction must not commit");
    } catch (SQLException e) {
      assertTrue(rootMessage(e).contains("unbalanced"), rootMessage(e));
    }
  }

  @Test
  void singlePostingAndOneSidedTransactionsRejected() throws Exception {
    try (Connection c = adminConnection()) {
      insertAccount(c, "one-asset");
    }
    try {
      insertTx(new String[][] {{"DEBIT", "5.0000", "one-asset"}});
      fail("single-sided transaction must not commit");
    } catch (SQLException e) {
      assertTrue(rootMessage(e).contains("unbalanced"), rootMessage(e));
    }
    try (Connection c = adminConnection()) {
      insertAccount(c, "one-liability");
    }
    try {
      insertTx(new String[][] {
          {"DEBIT", "5.0000", "one-asset"},
          {"DEBIT", "5.0000", "one-liability"}});
      fail("two-debit transaction must not commit");
    } catch (SQLException e) {
      assertTrue(rootMessage(e).contains("unbalanced"), rootMessage(e));
    }
  }

  @Test
  void postingsToFrozenOrClosedAccountsRejected() throws Exception {
    try (Connection c = adminConnection()) {
      insertAccount(c, "frozen-asset");
      insertAccount(c, "frozen-liability");
      try (Statement st = c.createStatement()) {
        st.executeUpdate("UPDATE ledger.ledger_account SET status = 'FROZEN' WHERE name = 'frozen-liability'");
      }
    }
    try {
      insertTx(new String[][] {
          {"DEBIT", "5.0000", "frozen-asset"},
          {"CREDIT", "5.0000", "frozen-liability"}});
      fail("posting to FROZEN account must not commit");
    } catch (SQLException e) {
      assertTrue(rootMessage(e).contains("non-ACTIVE"), rootMessage(e));
    }
  }

  @Test
  void journalIsImmutableEvenForTheOwner() throws Exception {
    try (Connection c = adminConnection();
        Statement st = c.createStatement()) {
      try {
        st.executeUpdate("UPDATE ledger.journal_posting SET amount = 1.0000");
        fail("UPDATE on journal_posting must be blocked");
      } catch (PSQLException e) {
        assertTrue(rootMessage(e).contains("append-only"), rootMessage(e));
      }
      try {
        st.executeUpdate("DELETE FROM ledger.journal_transaction");
        fail("DELETE on journal_transaction must be blocked");
      } catch (PSQLException e) {
        assertTrue(rootMessage(e).contains("append-only"), rootMessage(e));
      }
      try {
        st.executeUpdate("DELETE FROM ledger.ledger_account");
        fail("DELETE on ledger_account must be blocked");
      } catch (PSQLException e) {
        assertTrue(rootMessage(e).contains("append-only"), rootMessage(e));
      }
    }
  }

  private static String rootMessage(Throwable t) {
    String message = t.getMessage();
    Throwable cause = t.getCause();
    while (cause != null) {
      message = cause.getMessage();
      cause = cause.getCause();
    }
    return message == null ? "" : message;
  }
}
