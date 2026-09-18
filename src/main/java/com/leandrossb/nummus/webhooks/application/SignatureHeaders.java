package com.leandrossb.nummus.webhooks.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe-style delivery signature: {@code t=<epochSecond>,v1=<hex hmac-sha256(secret,
 * t + "." + payload)>}. Receivers recompute the MAC over the timestamped payload
 * and enforce a tolerance window on {@code t} to reject replays.
 */
public final class SignatureHeaders {

  private SignatureHeaders() {
  }

  public static String sign(String secret, String payload, Instant now) {
    String timestamp = String.valueOf(now.getEpochSecond());
    String mac = hmac(secret, timestamp + "." + payload);
    return "t=" + timestamp + ",v1=" + mac;
  }

  private static String hmac(String secret, String input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
