package com.leandrossb.nummus.interfaces.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

  private static final long SECOND = 1_000_000_000L;

  @Test
  void consumesUpToCapacityThenRefuses() {
    TokenBucket bucket = new TokenBucket(2, 1, 0);
    assertTrue(bucket.tryConsume(0));
    assertTrue(bucket.tryConsume(0));
    assertFalse(bucket.tryConsume(0));
  }

  @Test
  void refillAccruesOverElapsedTime() {
    TokenBucket bucket = new TokenBucket(2, 1, 0);
    assertTrue(bucket.tryConsume(0));
    assertTrue(bucket.tryConsume(0));
    assertFalse(bucket.tryConsume(0));
    // One second later exactly one token has accrued.
    assertTrue(bucket.tryConsume(SECOND));
    assertFalse(bucket.tryConsume(SECOND));
  }

  @Test
  void tokensClampAtCapacity() {
    TokenBucket bucket = new TokenBucket(2, 1000, 0);
    assertTrue(bucket.tryConsume(0));
    assertTrue(bucket.tryConsume(0));
    // An hour at 1000/s refills far beyond capacity; only two tokens exist.
    long hour = 3600 * SECOND;
    assertTrue(bucket.tryConsume(hour));
    assertTrue(bucket.tryConsume(hour));
    assertFalse(bucket.tryConsume(hour));
  }

  @Test
  void subTokenElapsedDoesNotConsume() {
    TokenBucket bucket = new TokenBucket(1, 1, 0);
    assertTrue(bucket.tryConsume(0));
    // 100ms accrues 0.1 tokens — not enough.
    assertFalse(bucket.tryConsume(SECOND / 10));
  }

  @Test
  void retryAfterSecondsCoversTheNextRefill() {
    TokenBucket bucket = new TokenBucket(1, 2, 0);
    assertTrue(bucket.tryConsume(0));
    // Empty at 2/s: the next token lands in 0.5s; the header rounds up to 1.
    assertTrue(bucket.retryAfterSeconds(0) >= 1);
    // Half a second later one token exists; tryConsume wins it and the
    // following refill estimate is again a whole second at most.
    assertTrue(bucket.tryConsume(SECOND / 2));
    assertTrue(bucket.retryAfterSeconds(SECOND / 2) >= 1);
  }
}
