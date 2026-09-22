package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.merchants.application.BootstrapAlreadyUsedException;
import com.leandrossb.nummus.merchants.application.InvalidBootstrapTokenException;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "nummus.operator.bootstrap-token=test-bootstrap-token")
class OperatorKeysServiceTest extends IntegrationTestBase {

  @Autowired
  private OperatorKeysService operatorKeys;

  @Test
  void bootstrapMintsOnceThenLocksOut() {
    revokeEveryActiveKey();
    int keysBefore = operatorKeys.list().size();

    // From a clean state the token comparison is reached: a wrong token is
    // rejected before anything is minted.
    assertThrows(InvalidBootstrapTokenException.class,
        () -> operatorKeys.bootstrap("wrong-token-with-same-length!!", "probe"));

    var first = operatorKeys.bootstrap("test-bootstrap-token", "probe");
    assertTrue(first.secret().startsWith("nummus_sk_"));
    assertTrue(operatorKeys.findByRawKey(first.secret()).isPresent());

    // The first mint consumed the one-time bootstrap: the same token is now
    // locked out even though it still matches.
    assertThrows(BootstrapAlreadyUsedException.class,
        () -> operatorKeys.bootstrap("test-bootstrap-token", "probe"));

    // Self-serve lifecycle still works after bootstrap is consumed.
    var minted = operatorKeys.create("probe", null);
    assertNotEquals(first.secret(), minted.secret());
    operatorKeys.revoke(minted.key().publicId());
    assertTrue(operatorKeys.findByRawKey(minted.secret()).isEmpty());
    assertEquals(keysBefore + 2, operatorKeys.list().size());
    assertThrows(com.leandrossb.nummus.merchants.application.UnknownApiKeyException.class,
        () -> operatorKeys.revoke(UUID.randomUUID()));
  }

  @Test
  void revokingEveryKeyReopensBootstrap() {
    revokeEveryActiveKey();
    var key = operatorKeys.create("probe", null);
    operatorKeys.revoke(key.key().publicId());
    var again = operatorKeys.bootstrap("test-bootstrap-token", "probe");
    assertTrue(again.secret().startsWith("nummus_sk_"));
  }

  /** The operator-key table is shared across methods and suites; every test
   * starts from no ACTIVE keys so JUnit's unspecified method order cannot
   * change the outcome. */
  private void revokeEveryActiveKey() {
    operatorKeys.list().stream()
        .filter(key -> "ACTIVE".equals(key.status()))
        .forEach(key -> operatorKeys.revoke(key.publicId()));
  }
}
