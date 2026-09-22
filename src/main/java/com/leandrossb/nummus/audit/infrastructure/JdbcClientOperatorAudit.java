package com.leandrossb.nummus.audit.infrastructure;

import com.leandrossb.nummus.audit.application.OperatorActionRecord;
import com.leandrossb.nummus.audit.application.OperatorAudit;
import com.leandrossb.nummus.audit.application.OperatorAuditLog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The audit log's single adapter: writes inside the caller's transaction
 *  (MANDATORY), reads for the listing (newest-first keyset). */
@Component
public class JdbcClientOperatorAudit implements OperatorAudit, OperatorAuditLog {

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;

  public JdbcClientOperatorAudit(JdbcClient jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(UUID actorKey, String action, String subjectType, UUID subjectId,
      Map<String, ?> detail) {
    jdbc.sql("""
        insert into audit.operator_action (public_id, actor_key, action, subject_type, subject_id, detail)
        values (:entryId, :actorKey, :action, :subjectType, :subjectId, :detail::jsonb)
        """)
        .param("entryId", UUID.randomUUID())
        .param("actorKey", actorKey)
        .param("action", action)
        .param("subjectType", subjectType)
        .param("subjectId", subjectId)
        .param("detail", detail == null || detail.isEmpty() ? "{}" : objectMapper.writeValueAsString(detail))
        .update();
  }

  /** Listing read: newest-first keyset; action is an optional exact filter. */
  @Override
  public List<OperatorActionRecord> list(UUID after, String action, int limit) {
    return jdbc.sql("""
        select a.public_id, a.actor_key, k.label as actor_label, a.action, a.subject_type,
               a.subject_id, a.detail::text, a.occurred_at
        from audit.operator_action a
        left join merchants.operator_key k on k.public_id = a.actor_key
        where (:after::uuid is null or a.id < (select f.id from audit.operator_action f
              where f.public_id = :after))
          and (:action::text is null or a.action = :action)
        order by a.id desc
        limit :limit
        """)
        .param("after", after)
        .param("action", action)
        .param("limit", limit)
        .query((rs, i) -> new OperatorActionRecord(
            rs.getObject("public_id", UUID.class),
            rs.getObject("actor_key", UUID.class),
            rs.getString("actor_label"),
            rs.getString("action"),
            rs.getString("subject_type"),
            rs.getObject("subject_id", UUID.class),
            readDetail(rs.getString("detail")),
            rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant()))
        .list();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readDetail(String json) {
    return json == null ? Map.of() : objectMapper.readValue(json, Map.class);
  }
}
