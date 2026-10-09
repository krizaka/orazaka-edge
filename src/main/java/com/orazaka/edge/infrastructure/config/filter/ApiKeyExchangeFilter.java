package com.orazaka.edge.infrastructure.config.filter;

import com.krizaka.security.token.ServiceTokenProvider;
import com.orazaka.edge.infrastructure.config.EdgeIdentityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exchanges inbound {@code oz_} API keys for session JWTs at the facade (Phase 2 cutover): the edge
 * calls the identity service's internal exchange endpoint and rewrites the {@code Authorization}
 * header, so downstream services only ever see JWTs. An invalid key is rejected here with 401 — it
 * never reaches a backend.
 *
 * <p>Deliberately uncached: API-key revocation must take effect immediately (the api-keys contract
 * asserts it). A revocation-aware cache can come later with an {@code evt.apikey.revoked}
 * invalidation.
 */
@Component
class ApiKeyExchangeFilter extends OncePerRequestFilter {

  private static final Logger logger = LoggerFactory.getLogger(ApiKeyExchangeFilter.class);
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String API_KEY_PREFIX = "oz_";

  private final RestClient identityClient;

  /** Wire shape of the internal exchange response (contract copy — no shared jar). */
  record ExchangeResponse(String token) {}

  ApiKeyExchangeFilter(RestClient proxyRestClient, EdgeIdentityProperties properties) {
    Objects.requireNonNull(properties, "EdgeIdentityProperties cannot be null");
    ServiceTokenProvider tokens =
        new ServiceTokenProvider(properties.serviceSecret(), "orazaka-edge");
    this.identityClient =
        proxyRestClient
            .mutate()
            .baseUrl(properties.baseUrl().toString())
            // /internal/v1/tokens/exchange is authenticated as of ADR-035. Without this the edge
            // cannot exchange an API key, and every `oz_` caller gets a 401 it cannot explain.
            .requestInitializer(request -> request.getHeaders().setBearerAuth(tokens.token()))
            .build();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String authorization = request.getHeader("Authorization");
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX + API_KEY_PREFIX)) {
      filterChain.doFilter(request, response);
      return;
    }
    String apiKey = authorization.substring(BEARER_PREFIX.length());
    String jwt;
    try {
      jwt = exchange(apiKey);
    } catch (ResourceAccessException e) {
      logger.warn("API-key exchange unreachable: {}", e.getMessage());
      response.setStatus(502);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\": \"Bad Gateway\"}");
      return;
    }
    if (jwt == null) {
      response.setStatus(401);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\": \"Invalid API key\"}");
      return;
    }
    filterChain.doFilter(new AuthorizationRewriter(request, BEARER_PREFIX + jwt), response);
  }

  /** Exchanges an API key for a session JWT; {@code null} when the key is invalid/revoked. */
  private String exchange(String apiKey) {
    ExchangeResponse exchanged =
        identityClient
            .post()
            .uri("/internal/v1/tokens/exchange")
            .header("Content-Type", "application/json")
            .body(Map.of("apiKey", apiKey))
            .exchange(
                (request, response) ->
                    response.getStatusCode().is2xxSuccessful()
                        ? response.bodyTo(ExchangeResponse.class)
                        : null);
    return exchanged != null ? exchanged.token() : null;
  }

  /** Request wrapper substituting the Authorization header with the minted JWT. */
  private static final class AuthorizationRewriter extends HttpServletRequestWrapper {

    private final String authorization;

    private AuthorizationRewriter(HttpServletRequest request, String authorization) {
      super(request);
      this.authorization = authorization;
    }

    @Override
    public String getHeader(String name) {
      return "Authorization".equalsIgnoreCase(name) ? authorization : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return "Authorization".equalsIgnoreCase(name)
          ? Collections.enumeration(java.util.List.of(authorization))
          : super.getHeaders(name);
    }
  }
}
