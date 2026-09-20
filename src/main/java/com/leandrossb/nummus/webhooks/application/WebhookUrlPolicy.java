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
          || address.isMulticastAddress() || isUniqueLocal(address)) {
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
}
