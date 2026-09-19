package com.leandrossb.nummus.interfaces.idempotency;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fails merchant writes closed: every POST under /v1 must carry a usable
 * Idempotency-Key. The path rule is defense in depth — @Idempotent is the real
 * mechanism, and a future merchant POST without it still gets a 400 here rather
 * than a silently non-idempotent write. Renders problem+json itself because a
 * filter runs outside the @ControllerAdvice's reach.
 * Authentication (MerchantAuthFilter) runs first.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1000)
public class IdempotencyWebFilter extends OncePerRequestFilter {

  public static final String KEY_HEADER = "Idempotency-Key";
  public static final String CACHED_BODY_ATTRIBUTE = "idempotency.cached-body";
  private static final int KEY_MAX_LENGTH = 255;

  private final ObjectMapper objectMapper;

  public IdempotencyWebFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, jakarta.servlet.ServletException {
    String key = request.getHeader(KEY_HEADER);
    if (key == null || key.isBlank() || key.length() > KEY_MAX_LENGTH) {
      ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
          "Idempotency-Key header (1-255 characters) is required on merchant writes");
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      response.setContentType("application/problem+json");
      response.getWriter().write(objectMapper.writeValueAsString(problem));
      return;
    }
    byte[] body = request.getInputStream().readAllBytes();
    request.setAttribute(CACHED_BODY_ATTRIBUTE, body);
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }
}
