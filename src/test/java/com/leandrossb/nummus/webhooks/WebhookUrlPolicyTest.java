package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.webhooks.application.UnsafeWebhookUrlException;
import com.leandrossb.nummus.webhooks.application.WebhookUrlPolicy;
import java.net.URI;
import org.junit.jupiter.api.Test;

class WebhookUrlPolicyTest {

  private static void assertRejected(String url) {
    assertThrows(UnsafeWebhookUrlException.class, () -> WebhookUrlPolicy.check(URI.create(url)), url);
  }

  @Test
  void httpsToPublicHostPasses() {
    assertDoesNotThrow(() -> WebhookUrlPolicy.check(URI.create("https://example.com/hook")));
  }

  @Test
  void httpToLoopbackLiteralPasses() {
    assertDoesNotThrow(() -> WebhookUrlPolicy.check(URI.create("http://127.0.0.1:9/hook")));
    assertDoesNotThrow(() -> WebhookUrlPolicy.check(URI.create("http://[::1]:9/hook")));
  }

  @Test
  void httpToLocalhostNamePasses() {
    assertDoesNotThrow(() -> WebhookUrlPolicy.check(URI.create("http://localhost:9/hook")));
  }

  @Test
  void privateRangesAreRejectedOverHttp() {
    assertRejected("http://10.1.2.3/hook");
    assertRejected("http://172.16.0.9/hook");
    assertRejected("http://192.168.1.1/hook");
  }

  @Test
  void privateRangesAreRejectedOverHttps() {
    assertRejected("https://10.1.2.3/hook");
    assertRejected("https://192.168.0.1/hook");
  }

  @Test
  void linkLocalIsRejectedIncludingCloudMetadata() {
    assertRejected("https://169.254.169.254/latest/meta-data");
    assertRejected("https://[fe80::1]/hook");
  }

  @Test
  void ipv6UniqueLocalIsRejected() {
    assertRejected("https://[fd00::1]/hook");
  }

  @Test
  void anyLocalIsRejected() {
    assertRejected("https://0.0.0.0/hook");
  }

  @Test
  void multicastIsRejected() {
    assertRejected("https://224.0.0.1/hook");
    assertRejected("https://[ff02::1]/hook");
  }

  @Test
  void userinfoIsRejected() {
    assertRejected("https://user:secret@example.com/hook");
  }

  @Test
  void unresolvableHostIsRejected() {
    assertRejected("https://no-such-host.invalid/hook");
  }

  @Test
  void nonHttpSchemeIsRejected() {
    assertRejected("ftp://example.com/hook");
  }
}
