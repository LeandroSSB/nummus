package com.leandrossb.nummus.webhooks.interfaces.dto;

import com.leandrossb.nummus.webhooks.application.DeliveryRecord;
import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
    UUID deliveryId, UUID eventId, String eventType, String status, int attempts,
    Integer lastResponseStatus, Instant nextAttemptAt) {

  public static DeliveryResponse from(DeliveryRecord record) {
    return new DeliveryResponse(record.deliveryPublicId(), record.eventPublicId(), record.eventType(),
        record.status(), record.attempts(), record.lastResponseStatus(), record.nextAttemptAt());
  }
}
