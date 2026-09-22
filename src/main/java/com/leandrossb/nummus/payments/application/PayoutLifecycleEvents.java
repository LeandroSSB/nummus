package com.leandrossb.nummus.payments.application;

/**
 * Producer-owned port implemented by the webhooks module (the M3 inversion:
 * the producing module defines the port, the consuming module adapts to it).
 * Implementations join the caller's transaction — publish after the guarded
 * transition succeeds, never before.
 */
public interface PayoutLifecycleEvents {

  void publish(PayoutLifecycleEvent event);
}
