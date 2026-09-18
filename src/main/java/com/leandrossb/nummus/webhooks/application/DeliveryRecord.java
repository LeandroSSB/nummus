package com.leandrossb.nummus.webhooks.application;

import java.time.Instant;
import java.util.UUID;

/** Read model for the deliveries listing. */
public record DeliveryRecord(
    long id, UUID eventPublicId, String eventType, String status,
    int attempts, Integer lastResponseStatus, Instant nextAttemptAt) {
}
