package com.leandrossb.nummus.audit.interfaces;

import com.leandrossb.nummus.audit.application.OperatorAuditLog;
import com.leandrossb.nummus.audit.interfaces.dto.AuditLogResponse;
import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The operator audit log listing: newest-first keyset pagination, mirroring
 *  the M10 pagination controllers. */
@RestController
class AuditLogController {

  private final OperatorAuditLog auditLog;

  AuditLogController(OperatorAuditLog auditLog) {
    this.auditLog = auditLog;
  }

  @GetMapping("/v1/operator/audit-log")
  ResponseEntity<List<AuditLogResponse>> list(AuthenticatedOperator operator,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    // Keyset pagination: fetch limit+1, hand back limit, and surface a
    // Next-Cursor (the last returned entry's public id) only when the probe
    // found an extra row.
    var page = auditLog.list(after, action, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(AuditLogResponse::from).toList());
    }
    var cursor = page.get(limit - 1).entryId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(AuditLogResponse::from).toList());
  }
}
