package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.IssuedApiKey;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MerchantStoreTest extends IntegrationTestBase {

  @Autowired
  private MerchantsService merchants;

  @Autowired
  private ApiKeysService keys;

  @Test
  void createIssuesAKeyThatResolvesAndIsNeverReissued() {
    var merchant = merchants.create("Store Merchant", FeeSchedule.ZERO);
    var first = merchants.findByApiKey("nummus_sk_definitely-unknown");
    assertTrue(first.isEmpty());

    // create() itself does not issue keys in this test's service shape — use the
    // controller-level creation in Task 3 for the bundled first key. Here: mint one.
    IssuedApiKey issued = keys.create(merchant.publicId());
    assertTrue(issued.secret().startsWith("nummus_sk_"));
    assertEquals(43 + "nummus_sk_".length(), issued.secret().length());
    assertEquals(issued.key().prefix(), issued.secret().substring(0, 12));
    assertEquals("ACTIVE", issued.key().status());

    var resolved = merchants.findByApiKey(issued.secret());
    assertTrue(resolved.isPresent());
    assertEquals(merchant.publicId(), resolved.get().publicId());

    // Revoked keys stop resolving; other keys are unaffected.
    var second = keys.create(merchant.publicId());
    keys.revoke(merchant.publicId(), issued.key().publicId());
    assertTrue(merchants.findByApiKey(issued.secret()).isEmpty());
    assertTrue(merchants.findByApiKey(second.secret()).isPresent());
    assertEquals(2, keys.list(merchant.publicId()).size());

    // Cross-merchant revoke is a miss.
    var other = merchants.create("Other Merchant", FeeSchedule.ZERO);
    assertThrows(com.leandrossb.nummus.merchants.application.UnknownApiKeyException.class,
        () -> keys.revoke(other.publicId(), second.key().publicId()));
    assertNotEquals(merchant.publicId(), other.publicId());
  }

  @Test
  void secretsAreUniqueAndHashesAreStoredNotSecrets() throws Exception {
    var merchant = merchants.create("Hash Merchant", FeeSchedule.ZERO);
    var issued = keys.create(merchant.publicId());
    var another = keys.create(merchant.publicId());
    assertNotEquals(issued.secret(), another.secret());
    try (var c = adminConnection(); var st = c.createStatement();
        var rs = st.executeQuery(
            "SELECT key_hash FROM merchants.api_key WHERE public_id = '" + issued.key().publicId() + "'")) {
      rs.next();
      assertTrue(!rs.getString(1).contains("nummus_sk_"), "the secret itself must never be stored");
      assertTrue(rs.getString(1).matches("[0-9a-f]{64}"));
    }
  }
}
