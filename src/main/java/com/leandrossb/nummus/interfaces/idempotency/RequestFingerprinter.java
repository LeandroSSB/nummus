package com.leandrossb.nummus.interfaces.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** SHA-256 over method, request URI, and raw body — the identity of a logical operation. */
public final class RequestFingerprinter {

  private RequestFingerprinter() {
  }

  public static byte[] sha256(String method, String uri, byte[] body) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(method.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      digest.update(uri.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      digest.update(body);
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
