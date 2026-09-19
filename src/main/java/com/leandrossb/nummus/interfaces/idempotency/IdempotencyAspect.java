package com.leandrossb.nummus.interfaces.idempotency;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.auth.MerchantAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * Reserve → execute → store, all in one transaction, so a committed business
 * write always has its stored response and a rolled-back one leaves nothing
 * behind. The transaction wraps only the handler method: domain exceptions
 * propagate, roll everything back, and are rendered by the advice outside this
 * layer — their retries re-execute deterministically.
 */
@Aspect
@Component
public class IdempotencyAspect {

  private final IdempotencyStore store;
  private final TransactionTemplate transactions;
  private final ObjectMapper objectMapper;
  private final IdempotencyProperties properties;

  public IdempotencyAspect(IdempotencyStore store, TransactionTemplate transactions,
      ObjectMapper objectMapper, IdempotencyProperties properties) {
    this.store = store;
    this.transactions = transactions;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  @Around("@annotation(com.leandrossb.nummus.interfaces.idempotency.Idempotent)")
  public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    HttpServletRequest request = currentRequest();
    String key = request.getHeader(IdempotencyWebFilter.KEY_HEADER);
    if (key == null || key.isBlank() || key.length() > 255) {
      throw new MissingIdempotencyKeyException();
    }
    UUID merchant = merchantNamespace(request);
    byte[] body = (byte[]) request.getAttribute(IdempotencyWebFilter.CACHED_BODY_ATTRIBUTE);
    if (body == null) {
      body = new byte[0];
    }
    byte[] fingerprint = RequestFingerprinter.sha256(request.getMethod(), request.getRequestURI(), body);
    Instant expiresAt = Instant.now().plus(properties.ttl());
    try {
      return transactions.execute(txStatus -> {
        store.insert(merchant, key, fingerprint, expiresAt);
        return proceedAndAttach(joinPoint, merchant, key);
      });
    } catch (DuplicateKeyException raced) {
      return raced(joinPoint, merchant, key, fingerprint, expiresAt, raced);
    }
  }

  /**
   * The caller's idempotency namespace: the authenticated merchant's, or the
   * operator's (null) when the request carries no merchant. The same namespace
   * reserves and replays — a merchant never sees another's stored response.
   */
  private static UUID merchantNamespace(HttpServletRequest request) {
    Object merchant = request.getAttribute(MerchantAuthFilter.MERCHANT_ATTRIBUTE);
    return merchant instanceof AuthenticatedMerchant authenticated ? authenticated.merchantPublicId() : null;
  }

  /** Runs the handler and attaches the serialized response — call inside an open transaction. */
  private Object proceedAndAttach(ProceedingJoinPoint joinPoint, UUID merchant, String key) {
    Object result;
    try {
      result = joinPoint.proceed();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
    store.attachResponse(merchant, key, toStoredResponse(result));
    return result;
  }

  /**
   * The unique index lost us the slot. The winner's commit made its row visible
   * with its response attached; if the winner rolled back, our insert above
   * would have succeeded and we would not be here.
   */
  private Object raced(ProceedingJoinPoint joinPoint, UUID merchant, String key, byte[] fingerprint,
      Instant expiresAt, DuplicateKeyException raced) throws Throwable {
    var row = store.findByKey(merchant, key).orElseThrow(() -> raced);
    boolean expired = !row.expiresAt().isAfter(Instant.now());
    if (row.response() == null || expired) {
      // An expired slot is free real estate: claim it and run as new — inside
      // the same transaction, so a failed re-execution rolls the row back to
      // its still-expired state and the next retry can reclaim cleanly.
      return transactions.execute(txStatus -> {
        if (!store.reclaimExpired(merchant, key, fingerprint, expiresAt)) {
          // Lost the reclaim race (a concurrent retry of the same expired key
          // claimed it first) — treat as reuse; the client retries shortly.
          throw new IdempotencyKeyReuseException(key);
        }
        return proceedAndAttach(joinPoint, merchant, key);
      });
    }
    if (!Arrays.equals(row.requestFingerprint(), fingerprint)) {
      throw new IdempotencyKeyReuseException(key);
    }
    return replay(row.response());
  }

  private Object replay(StoredResponse stored) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(stored.status())
        .header("Idempotency-Replayed", "true");
    if (stored.contentType() != null) {
      builder.contentType(MediaType.parseMediaType(stored.contentType()));
    }
    if (stored.location() != null) {
      builder.location(URI.create(stored.location()));
    }
    return builder.body(stored.body());
  }

  private StoredResponse toStoredResponse(Object result) {
    if (result instanceof ResponseEntity<?> entity) {
      String body = entity.getBody() == null ? null : objectMapper.writeValueAsString(entity.getBody());
      MediaType contentType = entity.getHeaders().getContentType();
      URI location = entity.getHeaders().getLocation();
      return new StoredResponse(entity.getStatusCode().value(),
          contentType == null ? MediaType.APPLICATION_JSON_VALUE : contentType.toString(),
          location == null ? null : location.toString(), body);
    }
    return new StoredResponse(200, MediaType.APPLICATION_JSON_VALUE, null,
        objectMapper.writeValueAsString(result));
  }

  private static HttpServletRequest currentRequest() {
    return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
  }
}
