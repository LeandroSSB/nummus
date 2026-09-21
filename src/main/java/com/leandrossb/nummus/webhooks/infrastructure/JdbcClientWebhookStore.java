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
          (public_id, merchant_public_id, url, secret, event_types, status, created_at)
        values (:publicId, :merchantPublicId, :url, :secret, :eventTypes::jsonb, :status, :createdAt)
        """)
        .param("publicId", endpoint.publicId())
        .param("merchantPublicId", endpoint.merchantPublicId())
        .param("url", endpoint.url().toString())
        .param("secret", endpoint.secret())
        .param("eventTypes", typesJson(endpoint.eventTypes()))
        .param("status", endpoint.status().name())
        .param("createdAt", toOffsetDateTime(endpoint.createdAt()))
        .update();
    return endpoint;
  }

  @Override
  public List<WebhookEndpoint> listActiveEndpoints(UUID merchantPublicId) {
    return jdbc.sql("""
        select public_id, merchant_public_id, url, secret, event_types::text, status, created_at
        from webhooks.webhook_endpoint
        -- null audience = the operator namespace
        where merchant_public_id is not distinct from :merchantPublicId and status = 'ACTIVE'
        order by created_at, id
        """)
        .param("merchantPublicId", merchantPublicId)
        .query((rs, i) -> mapEndpoint(rs)).list();
  }

  @Override
  public Optional<WebhookEndpoint> findActiveEndpoint(UUID merchantPublicId, UUID publicId) {
    return jdbc.sql("""
        select public_id, merchant_public_id, url, secret, event_types::text, status, created_at
        from webhooks.webhook_endpoint
        -- null audience = the operator namespace
        where merchant_public_id is not distinct from :merchantPublicId
          and public_id = :publicId and status = 'ACTIVE'
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("publicId", publicId)
        .query((rs, i) -> mapEndpoint(rs)).optional();
  }

  @Override
  public boolean markEndpointDeleted(UUID merchantPublicId, UUID publicId) {
    return jdbc.sql("""
        update webhooks.webhook_endpoint set status = 'DELETED'
        -- null audience = the operator namespace
        where merchant_public_id is not distinct from :merchantPublicId
          and public_id = :publicId and status = 'ACTIVE'
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("publicId", publicId).update() == 1;
  }

  @Override
  public void insertEvent(UUID eventPublicId, UUID audienceMerchant, String type, String payload,
      Instant occurredAt) {
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
    // is not distinct from binds a NULL audience to the operator namespace
    // (merchant_public_id is null) and a non-NULL one to the owning merchant —
    // one predicate, both audiences; no endpoint ever sees a foreign event.
    jdbc.sql("""
        insert into webhooks.webhook_delivery (event_id, endpoint_id)
        select e.id, p.id
        from webhooks.webhook_event e
        cross join webhooks.webhook_endpoint p
        where e.public_id = :eventPublicId
          and p.status = 'ACTIVE'
          and p.merchant_public_id is not distinct from :audienceMerchant
          and (jsonb_array_length(p.event_types) = 0 or p.event_types @> to_jsonb(:type))
        """)
        .param("eventPublicId", eventPublicId)
        .param("audienceMerchant", audienceMerchant)
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
  public boolean requeueFailedDelivery(UUID merchantPublicId, UUID deliveryPublicId) {
    return jdbc.sql("""
        update webhooks.webhook_delivery d
        set status = 'PENDING', attempts = 0, next_attempt_at = now()
        from webhooks.webhook_endpoint e
        where d.endpoint_id = e.id
          and d.public_id = :deliveryId
          and e.merchant_public_id = :merchantPublicId
          and d.status = 'FAILED'
        """)
        .param("deliveryId", deliveryPublicId)
        .param("merchantPublicId", merchantPublicId)
        .update() == 1;
  }

  @Override
  public List<DeliveryRecord> listDeliveries(UUID merchantPublicId, UUID endpointPublicId, String status,
      UUID after, int limit) {
    return jdbc.sql("""
        select d.public_id, d.id, e.public_id, e.type, d.status, d.attempts,
               d.last_response_status, d.next_attempt_at
        from webhooks.webhook_delivery d
        join webhooks.webhook_event e on e.id = d.event_id
        join webhooks.webhook_endpoint p on p.id = d.endpoint_id
        where p.merchant_public_id = :merchantPublicId
          and p.public_id = :endpointPublicId
          and (:status::text is null or d.status = :status)
          and (:after::uuid is null
               or d.id < (select d2.id from webhooks.webhook_delivery d2 where d2.public_id = :after))
        order by d.id desc
        limit :limit
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("endpointPublicId", endpointPublicId)
        .param("status", status)
        .param("after", after)
        .param("limit", limit)
        .query((rs, i) -> new DeliveryRecord(rs.getObject(1, UUID.class), rs.getLong(2),
            rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getInt(6),
            rs.getObject(7) == null ? null : rs.getInt(7),
            toInstant(rs.getObject(8, OffsetDateTime.class))))
        .list();
  }

  @Override
  public int pruneSucceededBefore(Instant cutoff, int batch) {
    int total = 0;
    int deleted;
    do {
      deleted = jdbc.sql("""
          delete from webhooks.webhook_delivery
          where id in (
            select id from webhooks.webhook_delivery
            where status = 'SUCCEEDED' and last_attempt_at < :cutoff
            limit :batch
          )
          """)
          .param("cutoff", toOffsetDateTime(cutoff))
          .param("batch", batch)
          .update();
      total += deleted;
    } while (deleted > 0);
    return total;
  }

  private static WebhookEndpoint mapEndpoint(ResultSet rs) throws SQLException {
    return new WebhookEndpoint(rs.getObject("merchant_public_id", UUID.class),
        rs.getObject("public_id", UUID.class), URI.create(rs.getString("url")),
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
