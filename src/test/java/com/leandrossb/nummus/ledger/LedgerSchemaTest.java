package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerSchemaTest extends IntegrationTestBase {

  @Test
  void schemaTablesAcceptRowsAndGeneratePublicIds() throws Exception {
    String accountPublicId = UUID.randomUUID().toString();
    String counterPublicId = UUID.randomUUID().toString();
    String txPublicId = UUID.randomUUID().toString();
    try (Connection c = adminConnection()) {
      try (Statement st = c.createStatement()) {
        st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
            + accountPublicId + "', 'schema test', 'ASSET')");
        st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
            + counterPublicId + "', 'schema test', 'LIABILITY')");
        st.executeUpdate("INSERT INTO ledger.journal_transaction (public_id, memo) VALUES ('"
            + txPublicId + "', 'schema test')");
        // Both legs in one statement: the deferred balance trigger fires at
        // transaction commit — under autocommit that is the statement's end —
        // and would reject a lone posting.
        st.executeUpdate("INSERT INTO ledger.journal_posting (transaction_id, account_id, direction, amount) "
            + "SELECT t.id, a.id, 'DEBIT', 10.0000 FROM ledger.journal_transaction t, ledger.ledger_account a "
            + "WHERE t.public_id = '" + txPublicId + "' AND a.public_id = '" + accountPublicId + "' "
            + "UNION ALL "
            + "SELECT t.id, a.id, 'CREDIT', 10.0000 FROM ledger.journal_transaction t, ledger.ledger_account a "
            + "WHERE t.public_id = '" + txPublicId + "' AND a.public_id = '" + counterPublicId + "'");
      }
      try (Statement st = c.createStatement();
          ResultSet rs = st.executeQuery(
              "SELECT currency, status FROM ledger.ledger_account WHERE public_id = '" + accountPublicId + "'")) {
        assertTrue(rs.next());
        assertEquals("BRL", rs.getString(1).trim());
        assertEquals("ACTIVE", rs.getString(2));
      }
    }
  }
}
