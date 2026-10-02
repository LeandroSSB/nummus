package com.leandrossb.nummus.payments.application;

/**
 * Producer-owned port implemented by the webhooks module (the M3 inversion:
 * the producing module defines the port, the consuming module adapts to it).
 * Implementations join the caller's transaction — publish after the row and
 * its journal entry commit together, never before.
 */
public interface TransferLifecycleEvents {

  void publish(TransferLifecycleEvent event);
}
