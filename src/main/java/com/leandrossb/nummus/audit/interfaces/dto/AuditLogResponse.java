package com.leandrossb.nummus.audit.interfaces.dto;

import com.leandrossb.nummus.audit.application.OperatorActionRecord;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One audit entry as the operator surface renders it: the recorded detail
 *  arrives as a JSON object; a null actor label (an actor with no key row)
 *  renders as null. */
public record AuditLogResponse(
    UUID entryId, String action, String subjectType, UUID subjectId,
    Map<String, Object> detail, Instant occurredAt, UUID actorKey, String actorLabel) {

  public static AuditLogResponse from(OperatorActionRecord entry) {
    return new AuditLogResponse(entry.entryId(), entry.action(), entry.subjectType(),
        entry.subjectId(), entry.detail(), entry.occurredAt(), entry.actorKey(),
        entry.actorLabel());
  }
}
