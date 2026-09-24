package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BankAccountsSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void schemaEnforcesShapeChecksAndThePartialUnique() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      String merchant = SeedMerchant.PUBLIC_ID.toString();
      String insert = "INSERT INTO merchants.bank_account (merchant_public_id, bank_code, branch,"
          + " account_number, holder_tax_id, verification_code_hash)"
          + " VALUES ('" + merchant + "', '%s', '4567', '99999-9', '11144477735', 'hash')";
      SQLException badBankCode = assertThrows(SQLException.class,
          () -> st.executeUpdate(insert.formatted("12")));
      assertEquals("23514", badBankCode.getSQLState());
      SQLException badTaxId = assertThrows(SQLException.class,
          () -> st.executeUpdate(insert.formatted("123").replace("'11144477735'", "1114447773")));
      assertEquals("23514", badTaxId.getSQLState());

      st.executeUpdate(insert.formatted("123"));
      SQLException duplicateActive = assertThrows(SQLException.class,
          () -> st.executeUpdate(insert.formatted("123")));
      assertEquals("23505", duplicateActive.getSQLState());
      st.executeUpdate("UPDATE merchants.bank_account SET status = 'REVOKED'"
          + " WHERE merchant_public_id = '" + merchant + "' AND account_number = '99999-9'");
      st.executeUpdate(insert.formatted("123"));
      SQLException badStatus = assertThrows(SQLException.class, () -> st.executeUpdate(
          "UPDATE merchants.bank_account SET status = 'WEIRD'"
              + " WHERE merchant_public_id = '" + merchant + "'"
              + " AND account_number = '99999-9'"));
      assertEquals("23514", badStatus.getSQLState());
    }
  }

  @Test
  void appRoleHasNoDeleteAndNoFullRowUpdate() throws Exception {
    // Column-scoped update: the app role cannot rewrite the code hash.
    try (var app = appConnection(); var st = app.createStatement()) {
      SQLException denied = assertThrows(SQLException.class, () -> st.executeUpdate(
          "UPDATE merchants.bank_account SET verification_code_hash = 'x'"
              + " WHERE merchant_public_id = '" + SeedMerchant.PUBLIC_ID + "'"));
      assertEquals("42501", denied.getSQLState());
    }
  }
}
