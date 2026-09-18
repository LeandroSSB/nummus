package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.webhooks.application.SignatureHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignatureHeadersTest {

  private static final Instant NOW = Instant.ofEpochSecond(1731571200);

  @Test
  void signProducesTimestampedHmacOverDotJoinedInput() throws Exception {
    String header = SignatureHeaders.sign("whsec_test", "{\"a\":1}", NOW);

    assertTrue(header.matches("t=1731571200,v1=[0-9a-f]{64}"), "format: " + header);

    // Independent HMAC computation — the receiver-side algorithm, mirrored.
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] expected = mac.doFinal("1731571200.{\"a\":1}".getBytes(StandardCharsets.UTF_8));
    String expectedHex = java.util.HexFormat.of().formatHex(expected);
    assertEquals("t=1731571200,v1=" + expectedHex, header);
  }

  @Test
  void signatureIsSensitiveToTimestampPayloadAndSecret() {
    String base = SignatureHeaders.sign("whsec_test", "{\"a\":1}", NOW);
    assertNotEquals(base, SignatureHeaders.sign("whsec_test", "{\"a\":1}", NOW.plusSeconds(1)));
    assertNotEquals(base, SignatureHeaders.sign("whsec_test", "{\"a\":2}", NOW));
    assertNotEquals(base, SignatureHeaders.sign("whsec_other", "{\"a\":1}", NOW));
    assertEquals(base, SignatureHeaders.sign("whsec_test", "{\"a\":1}", NOW));
  }
}
