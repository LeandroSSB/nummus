package com.leandrossb.nummus.audit.application;

import java.util.List;
import java.util.UUID;

/** Read port for the operator audit log: the newest-first keyset listing
 *  behind the operator surface. */
public interface OperatorAuditLog {

  /** @param after keyset anchor — only entries older than it are returned;
   *  null starts from the newest. An anchor that matches no row yields an
   *  empty page.
   *  @param action optional exact-match action filter; null disables it.
   *  @param limit maximum number of entries to return. */
  List<OperatorActionRecord> list(UUID after, String action, int limit);
}
