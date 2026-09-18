package com.leandrossb.nummus.webhooks.infrastructure;

import com.leandrossb.nummus.webhooks.application.DeliveryRecord;
import com.leandrossb.nummus.webhooks.application.DueDelivery;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientWebhookStore implements WebhookStore {

  private final JdbcClient jdbc;

  public JdbcClientWebhookStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public WebhookEndpoint insertEndpoint(WebhookEndpoint endpoint) {
    jdbc.sql("""
        insert into webhooks.webhook_endpoint
          (public_id, url, secret, event_types, status, created_at)
        values (:publicId, :url, :secret, :eventTypes::jsonb, :status, :createdAt)
        """)
        .param("publicId", endpoint.publicId())
        .param("url", endpoint.url().toString())
        .param("secret", endpoint.secret())
        .param("eventTypes", typesJson(endpoint.eventTypes()))
        .param("status", endpoint.status().name())
        .param("createdAt", toOffsetDateTime(endpoint.createdAt()))
        .update();
    return endpoint;
  }

  @Override
  public List<WebhookEndpoint> listActiveEndpoints() {
    return jdbc.sql("""
        select public_id, url, secret, event_types::text, status, created_at
        from webhooks.webhook_endpoint where status = 'ACTIVE' order by created_at, id
        """)
        .query((rs, i) -> mapEndpoint(rs)).list();
  }

  @Override
  public Optional<WebhookEndpoint> findActiveEndpoint(UUID publicId) {
    return jdbc.sql("""
        select public_id, url, secret, event_types::text, status, created_at
        from webhooks.webhook_endpoint where public_id = :publicId and status = 'ACTIVE'
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapEndpoint(rs)).optional();
  }

  @Override
  public boolean markEndpointDeleted(UUID publicId) {
    return jdbc.sql("""
        update webhooks.webhook_endpoint set status = 'DELETED'
        where public_id = :publicId and status = 'ACTIVE'
        """)
        .param("publicId", publicId).update() == 1;
  }

  @Override
  public void insertEvent(UUID eventPublicId, String type, String payload, Instant occurredAt) {
    jdbc.sql("""
        insert into webhooks.webhook_event (public_id, type, payload, occurred_at)
        values (:publicId, :type, :payload, :occurredAt)
        """)
        .param("publicId", eventPublicId)
        .param("type", type)
        .param("payload", payload)
        .param("occurredAt", toOffsetDateTime(occurredAt))
        .update();
    // Write-time fan-out: subscription semantics are exact at the event instant.
    jdbc.sql("""
        insert into webhooks.webhook_delivery (event_id, endpoint_id)
        select e.id, p.id
        from webhooks.webhook_event e
        cross join webhooks.webhook_endpoint p
        where e.public_id = :eventPublicId
          and p.status = 'ACTIVE'
          and (jsonb_array_length(p.event_types) = 0 or p.event_types @> to_jsonb(:type))
        """)
        .param("eventPublicId", eventPublicId)
        .param("type", type)
        .update();
  }

  @Override
  public List<DueDelivery> claimDueDeliveries(Instant now, int limit) {
    return jdbc.sql("""
        select d.id, p.status as endpoint_status, p.url, p.secret,
               e.type as event_type, e.payload, d.attempts
        from webhooks.webhook_delivery d
        join webhooks.webhook_event e on e.id = d.event_id
        join webhooks.webhook_endpoint p on p.id = d.endpoint_id
        where d.status = 'PENDING' and d.next_attempt_at <= :now
        order by d.id
        limit :limit
        """)
        .param("now", toOffsetDateTime(now))
        .param("limit", limit)
        .query((rs, i) -> new DueDelivery(rs.getLong(1),
            EndpointStatus.valueOf(rs.getString(2)), URI.create(rs.getString(3)),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7)))
        .list();
  }

  @Override
  public void recordDeliverySuccess(long deliveryId, Integer responseStatus) {
    jdbc.sql("""
        update webhooks.webhook_delivery
        set status = 'SUCCEEDED', last_attempt_at = now(), last_response_status = :responseStatus
        where id = :id and status = 'PENDING'
        """)
        .param("responseStatus", responseStatus)
        .param("id", deliveryId).update();
  }

  @Override
  public void recordDeliveryRetry(long deliveryId, Integer responseStatus, Instant nextAttemptAt) {
    jdbc.sql("""
        update webhooks.webhook_delivery
        set attempts = attempts + 1, next_attempt_at = :nextAttemptAt,
            last_attempt_at = now(), last_response_status = :responseStatus
        where id = :id and status = 'PENDING'
        """)
        .param("nextAttemptAt", toOffsetDateTime(nextAttemptAt))
        .param("responseStatus", responseStatus)
        .param("id", deliveryId).update();
  }

  @Override
  public void recordDeliveryFailure(long deliveryId, Integer responseStatus) {
    jdbc.sql("""
        update webhooks.webhook_delivery
        set status = 'FAILED', attempts = attempts + 1,
            last_attempt_at = now(), last_response_status = :responseStatus
        where id = :id and status = 'PENDING'
        """)
        .param("responseStatus", responseStatus)
        .param("id", deliveryId).update();
  }

  @Override
  public List<DeliveryRecord> listDeliveries(UUID endpointPublicId, String status, int limit) {
    return jdbc.sql("""
        select d.id, e.public_id, e.type, d.status, d.attempts,
               d.last_response_status, d.next_attempt_at
        from webhooks.webhook_delivery d
        join webhooks.webhook_event e on e.id = d.event_id
        join webhooks.webhook_endpoint p on p.id = d.endpoint_id
        where p.public_id = :endpointPublicId
          and (:status::text is null or d.status = :status)
        order by d.id desc
        limit :limit
        """)
        .param("endpointPublicId", endpointPublicId)
        .param("status", status)
        .param("limit", limit)
        .query((rs, i) -> new DeliveryRecord(rs.getLong(1), rs.getObject(2, UUID.class),
            rs.getString(3), rs.getString(4), rs.getInt(5),
            rs.getObject(6) == null ? null : rs.getInt(6),
            toInstant(rs.getObject(7, OffsetDateTime.class))))
        .list();
  }

  private static WebhookEndpoint mapEndpoint(ResultSet rs) throws SQLException {
    return new WebhookEndpoint(rs.getObject("public_id", UUID.class), URI.create(rs.getString("url")),
        rs.getString("secret"), typesFromJson(rs.getString("event_types")),
        EndpointStatus.valueOf(rs.getString("status")),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)));
  }

  private static String typesJson(List<String> types) {
    if (types == null || types.isEmpty()) {
      return "[]";
    }
    return types.stream().map(t -> "\"" + t + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static List<String> typesFromJson(String json) {
    if (json == null) {
      return List.of();
    }
    String trimmed = json.trim();
    if (trimmed.length() <= 2) {
      return List.of();
    }
    return java.util.Arrays.stream(trimmed.substring(1, trimmed.length() - 1).split(","))
        .map(s -> s.trim().replace("\"", ""))
        .filter(s -> !s.isEmpty())
        .toList();
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant.atOffset(java.time.ZoneOffset.UTC);
  }

  private static Instant toInstant(OffsetDateTime timestamp) {
    return timestamp.toInstant();
  }
}
