package com.leandrossb.nummus.interfaces.ratelimit;

/**
 * Classic token bucket for per-tenant request throttling: bursts up to
 * {@code capacity}, refilled at {@code refillPerSecond} tokens per second,
 * computed lazily from the caller-supplied monotonic nanosecond stamp — no
 * background threads. The fractional token arithmetic is rate math, not
 * money; double is deliberate (the BigDecimal rule is about money).
 */
final class TokenBucket {

  private final int capacity;
  private final int refillPerSecond;
  private double tokens;
  private long lastRefillNanos;

  TokenBucket(int capacity, int refillPerSecond, long nowNanos) {
    this.capacity = capacity;
    this.refillPerSecond = refillPerSecond;
    this.tokens = capacity;
    this.lastRefillNanos = nowNanos;
  }

  /** Consumes one token if a full token is available. */
  synchronized boolean tryConsume(long nowNanos) {
    refill(nowNanos);
    if (tokens >= 1.0) {
      tokens -= 1.0;
      return true;
    }
    return false;
  }

  /** Whole seconds until at least one full token is available; always at least 1. */
  synchronized int retryAfterSeconds(long nowNanos) {
    refill(nowNanos);
    double deficit = 1.0 - tokens;
    if (deficit <= 0.0) {
      return 1;
    }
    return Math.max(1, (int) Math.ceil(deficit / refillPerSecond));
  }

  private void refill(long nowNanos) {
    if (nowNanos <= lastRefillNanos) {
      return;
    }
    double added = (nowNanos - lastRefillNanos) / 1_000_000_000.0 * refillPerSecond;
    tokens = Math.min(capacity, tokens + added);
    lastRefillNanos = nowNanos;
  }
}
