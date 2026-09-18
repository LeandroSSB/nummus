package com.leandrossb.nummus.interfaces.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
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
    byte[] fingerprint = RequestFingerprinter.sha256(request.getMethod(), request.getRequestURI(),
        (byte[]) request.getAttribute(IdempotencyWebFilter.CACHED_BODY_ATTRIBUTE));
    Instant expiresAt = Instant.now().plus(properties.ttl());
    try {
      return transactions.execute(txStatus -> {
        store.insert(key, fingerprint, expiresAt);
        return proceedAndAttach(joinPoint, key);
      });
    } catch (DuplicateKeyException raced) {
      return raced(joinPoint, key, fingerprint, expiresAt, raced);
    }
  }

  /** Runs the handler and attaches the serialized response — call inside an open transaction. */
  private Object proceedAndAttach(ProceedingJoinPoint joinPoint, String key) {
    Object result;
    try {
      result = joinPoint.proceed();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
    store.attachResponse(key, toStoredResponse(result));
    return result;
  }

  /**
   * The unique index lost us the slot. The winner's commit made its row visible
   * with its response attached; if the winner rolled back, our insert above
   * would have succeeded and we would not be here.
   */
  private Object raced(ProceedingJoinPoint joinPoint, String key, byte[] fingerprint,
      Instant expiresAt, DuplicateKeyException raced) throws Throwable {
    var row = store.findByKey(key).orElseThrow(() -> raced);
    boolean expired = !row.expiresAt().isAfter(Instant.now());
    if (row.response() == null || expired) {
      // An expired slot is free real estate: claim it and run as new. Losing
      // the reclaim race means a concurrent request claimed it — treat as reuse.
      if (!store.reclaimExpired(key, fingerprint, expiresAt)) {
        throw new IdempotencyKeyReuseException(key);
      }
      return transactions.execute(txStatus -> proceedAndAttach(joinPoint, key));
    }
    if (!Arrays.equals(row.requestFingerprint(), fingerprint)) {
      throw new IdempotencyKeyReuseException(key);
    }
    return replay(joinPoint, row.response());
  }

  /**
   * The proxy requires the returned value to be assignable to the handler's
   * declared return type. Handlers declaring ResponseEntity get the stored
   * envelope back verbatim; handlers returning a DTO cannot receive one, so
   * the stored body is deserialized and rendered through the normal MVC path
   * (same 200, same JSON) with the replay flag set on the response directly.
   */
  private Object replay(ProceedingJoinPoint joinPoint, StoredResponse stored) {
    Class<?> declared = ((MethodSignature) joinPoint.getSignature()).getReturnType();
    if (!ResponseEntity.class.isAssignableFrom(declared)) {
      currentResponse().setHeader("Idempotency-Replayed", "true");
      return stored.body() == null ? null : objectMapper.readValue(stored.body(), declared);
    }
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

  private static HttpServletResponse currentResponse() {
    return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getResponse();
  }
}
