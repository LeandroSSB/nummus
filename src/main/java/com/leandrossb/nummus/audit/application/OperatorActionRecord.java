package com.leandrossb.nummus.audit.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One audited operator action, newest-first from the listing. */
public record OperatorActionRecord(UUID entryId, UUID actorKey, String actorLabel, String action,
    String subjectType, UUID subjectId, Map<String, Object> detail, Instant occurredAt) {
}
