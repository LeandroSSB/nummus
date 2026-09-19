package com.leandrossb.nummus.interfaces.auth;

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
 * Resolves Bearer credentials to a merchant before anything else runs (the
 * idempotency filter sits at HIGHEST_PRECEDENCE + 1000 — no auth means 401,
 * never a 400). Merchant routes (MERCHANT_ROUTES and their subpaths) are rejected right here
 * when credentials are missing, invalid, or revoked; elsewhere a failed
 * Bearer still 401s while credential-less requests pass through to the
 * operator surface. Renders 401 itself: a filter runs outside the advice's
 * reach. The argument resolver is the fail-closed backstop for merchant
 * handlers this path rule does not know about.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MerchantAuthFilter extends OncePerRequestFilter {

  public static final String MERCHANT_ATTRIBUTE = "auth.merchant";
  private static final String BEARER_PREFIX = "Bearer ";

  /** Routes that require an authenticated merchant (headerless → 401, not a later 400). */
  private static final java.util.List<String> MERCHANT_ROUTES =
      java.util.List.of("/v1/me", "/v1/accounts", "/v1/payment-intents");

  private final MerchantAuthenticationPort authentication;
  private final ObjectMapper objectMapper;

  public MerchantAuthFilter(MerchantAuthenticationPort authentication, ObjectMapper objectMapper) {
    this.authentication = authentication;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, jakarta.servlet.ServletException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      if (isMerchantRoute(request.getRequestURI())) {
        reject(response);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    var merchant = authentication.authenticate(header.substring(BEARER_PREFIX.length()));
    if (merchant.isEmpty()) {
      reject(response);
      return;
    }
    request.setAttribute(MERCHANT_ATTRIBUTE, merchant.get());
    chain.doFilter(request, response);
  }

  /** Each route exactly or anything below it — never a longer path that merely
   *  shares the prefix (e.g. {@code /v1/me} does not cover {@code /v1/merchants}):
   *  the {@code prefix + "/"} guard is load-bearing. */
  private static boolean isMerchantRoute(String uri) {
    return MERCHANT_ROUTES.stream().anyMatch(
        prefix -> uri.equals(prefix) || uri.startsWith(prefix + "/"));
  }

  private void reject(HttpServletResponse response) throws IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
        new MerchantUnauthorizedException().getMessage());
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType("application/problem+json");
    response.getWriter().write(objectMapper.writeValueAsString(problem));
  }
}
