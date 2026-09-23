package com.leandrossb.nummus.interfaces.auth;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves Bearer credentials before anything else runs (the idempotency
 * filter sits at HIGHEST_PRECEDENCE + 1000 — no auth means 401, never a 400).
 * Resolution is two-stage: the operator hash first, then the merchant hash;
 * exactly one attribute is set, so role mismatches stay detectable downstream.
 * Protected routes (merchant and operator lists, and their subpaths) are
 * rejected right here when credentials are missing; elsewhere a failed
 * Bearer still 401s while credential-less requests pass through. The one
 * exemption is the bootstrap path, which must stay reachable token-first.
 * Renders 401 itself: a filter runs outside the advice's reach. The argument
 * resolvers are the fail-closed backstop for handlers this path rule does
 * not know about.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MerchantAuthFilter extends OncePerRequestFilter {

  public static final String MERCHANT_ATTRIBUTE = "auth.merchant";
  public static final String OPERATOR_ATTRIBUTE = "auth.operator";
  private static final String BEARER_PREFIX = "Bearer ";

  /** Routes that require an authenticated merchant (headerless → 401, not a later 400). */
  private static final List<String> MERCHANT_ROUTES =
      List.of("/v1/me", "/v1/accounts", "/v1/payment-intents", "/v1/payouts", "/v1/refunds",
          "/v1/webhook-endpoints", "/v1/webhook-deliveries");

  /** Routes that require an authenticated operator (headerless → 401, not a later 400). */
  private static final List<String> OPERATOR_ROUTES =
      List.of("/v1/merchants", "/v1/conciliation", "/v1/operator");

  /** Exact match, checked before the prefix logic — the prefix list would
   *  otherwise gate the recovery path behind the very keys it mints. The
   *  single source of truth for the exemption; the idempotency filter
   *  references it so the two can never drift apart. */
  public static final String BOOTSTRAP_PATH = "/v1/operator/bootstrap";

  private final MerchantAuthenticationPort merchantAuthentication;
  private final OperatorAuthenticationPort operatorAuthentication;
  private final ObjectMapper objectMapper;

  public MerchantAuthFilter(MerchantAuthenticationPort merchantAuthentication,
      OperatorAuthenticationPort operatorAuthentication, ObjectMapper objectMapper) {
    this.merchantAuthentication = merchantAuthentication;
    this.operatorAuthentication = operatorAuthentication;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, jakarta.servlet.ServletException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      if (isProtectedRoute(request.getRequestURI())) {
        reject(response);
        return;
      }
      chain.doFilter(request, response);
      return;
    }
    String credential = header.substring(BEARER_PREFIX.length());
    var operator = operatorAuthentication.authenticate(credential);
    if (operator.isPresent()) {
      request.setAttribute(OPERATOR_ATTRIBUTE, operator.get());
      chain.doFilter(request, response);
      return;
    }
    var merchant = merchantAuthentication.authenticate(credential);
    if (merchant.isEmpty()) {
      reject(response);
      return;
    }
    request.setAttribute(MERCHANT_ATTRIBUTE, merchant.get());
    chain.doFilter(request, response);
  }

  /** Headerless requests are rejected on merchant OR operator routes; the
   *  bootstrap exemption is exact-match and comes before the prefix logic. */
  private static boolean isProtectedRoute(String uri) {
    return !BOOTSTRAP_PATH.equals(uri) && (isMerchantRoute(uri) || isOperatorRoute(uri));
  }

  /** Each route exactly or anything below it — never a longer path that merely
   *  shares the prefix (e.g. {@code /v1/me} does not cover {@code /v1/merchants}):
   *  the {@code prefix + "/"} guard is load-bearing. */
  private static boolean isMerchantRoute(String uri) {
    return MERCHANT_ROUTES.stream().anyMatch(
        prefix -> uri.equals(prefix) || uri.startsWith(prefix + "/"));
  }

  private static boolean isOperatorRoute(String uri) {
    return OPERATOR_ROUTES.stream().anyMatch(
        prefix -> uri.equals(prefix) || uri.startsWith(prefix + "/"));
  }

  private void reject(HttpServletResponse response) throws IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
        new MerchantUnauthorizedException().getMessage());
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setContentType("application/problem+json");
    response.getWriter().write(objectMapper.writeValueAsString(problem));
  }
}
