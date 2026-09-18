package com.leandrossb.nummus.webhooks.application;

/** Outcome of one delivery attempt; httpStatus is null when no HTTP response was received. */
public record DeliveryResult(boolean delivered, Integer httpStatus) {
}
