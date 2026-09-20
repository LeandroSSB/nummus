package com.leandrossb.nummus.webhooks.application;

/** Raised when a webhook URL violates the SSRF policy. */
public class UnsafeWebhookUrlException extends RuntimeException {

  public UnsafeWebhookUrlException(String message) {
    super(message);
  }
}
