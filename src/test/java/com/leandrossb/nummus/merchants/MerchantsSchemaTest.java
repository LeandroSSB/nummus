package com.leandrossb.nummus.merchants;

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

class MerchantsSchemaTest extends IntegrationTestBase {

  @Test
  void seedMerchantBackfillsOwnershipAndIdempotencyNamespacesSplit() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery(
          "SELECT name FROM merchants.merchant WHERE public_id = '11111111-1111-4111-8111-111111111111'")) {
        assertTrue(rs.next());
        assertEquals("seed merchant", rs.getString(1));
      }
      // Ownership backfill: any pre-existing row carries the seed merchant.
      try (ResultSet rs = st.executeQuery(
          "SELECT count(*) FROM accounts.payment_account WHERE merchant_public_id IS NULL")) {
        rs.next();
        assertEquals(0, rs.getInt(1));
      }
      st.executeUpdate("INSERT INTO merchants.merchant (public_id, name) VALUES ('"
          + UUID.randomUUID() + "', 'A')");
      st.executeUpdate("INSERT INTO merchants.merchant (public_id, name) VALUES ('"
          + UUID.randomUUID() + "', 'B')");
      // api_key.key_hash is globally unique — authentication resolves a key by
      // hash alone — so replaying a hash, even across merchants, is rejected.
      st.executeUpdate("""
          INSERT INTO merchants.api_key (merchant_id, key_hash, prefix)
          SELECT id, 'hash-a', 'nummus_s' FROM merchants.merchant WHERE name = 'A'
          """);
      st.executeUpdate("""
          INSERT INTO merchants.api_key (merchant_id, key_hash, prefix)
          SELECT id, 'hash-b', 'nummus_s' FROM merchants.merchant WHERE name = 'B'
          """);
      SQLException replayedHash = assertThrows(SQLException.class, () -> st.executeUpdate("""
          INSERT INTO merchants.api_key (merchant_id, key_hash, prefix)
          SELECT id, 'hash-a', 'nummus_s' FROM merchants.merchant WHERE name = 'B'
          """));
      assertEquals("23505", replayedHash.getSQLState());

      // Idempotency keys split into two namespaces: the same key string across
      // a merchant and the operator namespace is allowed; repeating it within
      // the operator namespace is not.
      String sharedKey = "idem-" + UUID.randomUUID();
      st.executeUpdate("INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at, merchant_public_id)"
          + " VALUES ('" + sharedKey + "', decode('00','hex'), now() + interval '1 hour', '11111111-1111-4111-8111-111111111111')");
      st.executeUpdate("INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at, merchant_public_id)"
          + " VALUES ('" + sharedKey + "', decode('00','hex'), now() + interval '1 hour', NULL)");
      SQLException secondOperatorRow = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at, merchant_public_id)"
              + " VALUES ('" + sharedKey + "', decode('01','hex'), now() + interval '1 hour', NULL)"));
      assertEquals("23505", secondOperatorRow.getSQLState());
    }
  }
}
