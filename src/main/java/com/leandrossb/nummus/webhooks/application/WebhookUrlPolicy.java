package com.leandrossb.nummus.webhooks.application;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * The SSRF stance, enforced at registration and again before every delivery
 * attempt: https only, except plain http whose host resolves exclusively to
 * loopback (local receivers). Redirects are never followed by the delivery
 * client, so a public URL cannot pivot through a 3xx either.
 */
public final class WebhookUrlPolicy {

  /** Special-purpose IPv4 ranges the JDK's address-class checks do not
   *  cover: {network, mask} pairs over the unsigned 32-bit form. */
  private static final long[][] BLOCKED_V4_RANGES = {
      {0x64400000L, 0xffc00000L}, // 100.64.0.0/10   CGNAT
      {0xc0000200L, 0xffffff00L}, // 192.0.2.0/24    TEST-NET 1
      {0xc6336400L, 0xffffff00L}, // 198.51.100.0/24 TEST-NET 2
      {0xcb007100L, 0xffffff00L}, // 203.0.113.0/24  TEST-NET 3
      {0xc6120000L, 0xfffe0000L}, // 198.18.0.0/15   benchmarking
      {0xf0000000L, 0xf0000000L}, // 240.0.0.0/4     reserved
  };

  private WebhookUrlPolicy() {
  }

  public static void check(URI url) {
    if (url.getRawUserInfo() != null) {
      throw new UnsafeWebhookUrlException("url must not carry credentials: " + url.getHost());
    }
    String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase();
    boolean https = "https".equals(scheme);
    boolean http = "http".equals(scheme);
    if (!https && !http) {
      throw new UnsafeWebhookUrlException("url scheme must be http or https");
    }
    InetAddress[] addresses = resolve(url);
    if (http && allLoopback(addresses)) {
      return; // the local-receiver exemption
    }
    if (!https) {
      throw new UnsafeWebhookUrlException("http is allowed only for loopback hosts");
    }
    for (InetAddress address : addresses) {
      if (address.isLoopbackAddress() || address.isLinkLocalAddress()
          || address.isSiteLocalAddress() || address.isAnyLocalAddress()
          || address.isMulticastAddress() || isUniqueLocal(address)
          || isSpecialPurpose(address)) {
        throw new UnsafeWebhookUrlException(
            "url resolves to a blocked address class: " + address.getHostAddress());
      }
    }
  }

  public static boolean isSafe(URI url) {
    try {
      check(url);
      return true;
    } catch (UnsafeWebhookUrlException e) {
      return false;
    }
  }

  private static InetAddress[] resolve(URI url) {
    try {
      return InetAddress.getAllByName(url.getHost());
    } catch (UnknownHostException e) {
      throw new UnsafeWebhookUrlException("url host does not resolve: " + url.getHost());
    }
  }

  private static boolean allLoopback(InetAddress[] addresses) {
    for (InetAddress address : addresses) {
      if (!address.isLoopbackAddress()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isUniqueLocal(InetAddress address) {
    return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
  }

  /** Range blocks the JDK's class checks miss. IPv4-mapped IPv6 literals
   *  need no entry of their own: the resolver normalizes them to
   *  Inet4Address, which lands in the v4 table above. */
  private static boolean isSpecialPurpose(InetAddress address) {
    byte[] octets = address.getAddress();
    if (octets.length == 4) {
      long v4 = (octets[0] & 0xffL) << 24 | (octets[1] & 0xffL) << 16
          | (octets[2] & 0xffL) << 8 | (octets[3] & 0xffL);
      for (long[] cidr : BLOCKED_V4_RANGES) {
        if ((v4 & cidr[1]) == cidr[0]) {
          return true;
        }
      }
      return false;
    }
    return (octets[0] & 0xff) == 0x20 && (octets[1] & 0xff) == 0x01 // 2001:db8::/32,
        && (octets[2] & 0xff) == 0x0d && (octets[3] & 0xff) == 0xb8; // documentation only
  }
}
