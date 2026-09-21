package com.leandrossb.nummus.interfaces.ratelimit;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import com.leandrossb.nummus.interfaces.auth.MerchantAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-tenant token-bucket throttling on authenticated routes. Runs after
 * MerchantAuthFilter (the tenant is known) and before IdempotencyWebFilter
 * (a rejected request buffers no body). Merchant requests bucket by merchant
 * id — key rotation must not buy fresh quota; operator requests bucket by
 * operator key id. Unauthenticated requests pass through (the 401 path and
 * the open simulator stay unthrottled — documented bound). Renders
 * problem+json itself because a filter runs outside the @ControllerAdvice's
 * reach.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 500)
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimitProperties properties;
  private final ObjectMapper objectMapper;
  private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

  public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, jakarta.servlet.ServletException {
    Object merchant = request.getAttribute(MerchantAuthFilter.MERCHANT_ATTRIBUTE);
    Object operator = request.getAttribute(MerchantAuthFilter.OPERATOR_ATTRIBUTE);
    if (merchant instanceof AuthenticatedMerchant tenant) {
      if (!consume("merchant:" + tenant.merchantPublicId(), properties.merchantCapacity(),
          properties.merchantRefillPerSecond(), response)) {
        return;
      }
    } else if (operator instanceof AuthenticatedOperator principal) {
      if (!consume("operator:" + principal.keyPublicId(), properties.operatorCapacity(),
          properties.operatorRefillPerSecond(), response)) {
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private boolean consume(String bucketKey, int capacity, int refillPerSecond,
      HttpServletResponse response) throws IOException {
    TokenBucket bucket = buckets.computeIfAbsent(bucketKey,
        k -> new TokenBucket(capacity, refillPerSecond, System.nanoTime()));
    if (bucket.tryConsume(System.nanoTime())) {
      return true;
    }
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", String.valueOf(bucket.retryAfterSeconds(System.nanoTime())));
    response.setContentType("application/problem+json");
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
        "Rate limit exceeded; retry after the Retry-After delay");
    response.getWriter().write(objectMapper.writeValueAsString(problem));
    return false;
  }
}
