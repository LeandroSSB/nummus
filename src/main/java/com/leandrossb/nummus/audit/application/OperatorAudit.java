package com.leandrossb.nummus.audit.application;

import java.util.Map;
import java.util.UUID;

/** Write port for the operator audit log. Implementations record inside the
 *  action's transaction: an entry commits with the action or not at all. */
public interface OperatorAudit {

  void record(UUID actorKey, String action, String subjectType, UUID subjectId,
      Map<String, ?> detail);
}
