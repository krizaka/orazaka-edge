package com.krizaka.orazaka.edge.infrastructure.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.edge.infrastructure.config.EdgeIdentityProperties;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;

/**
 * Behavioral test of the oz_ exchange filter against a JDK stub of the identity exchange endpoint:
 * header rewrite, pass-through of non-oz_ tokens, 401 on invalid keys, and one live exchange per
 * request (uncached, so revocation is immediate).
 */
class ApiKeyExchangeFilterTest {

  private static HttpServer stub;
  private static final AtomicInteger exchangeHits = new AtomicInteger();

  @BeforeAll
  static void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext(
        "/internal/v1/tokens/exchange",
        exchange -> {
          exchangeHits.incrementAndGet();
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          boolean valid = body.contains("oz_valid") || body.contains("oz_scoped");
          String scopes = body.contains("oz_scoped") ? "[\"ai:embed\"]" : "[]";
          byte[] response =
              (valid ? "{\"token\":\"minted-jwt\",\"scopes\":" + scopes + "}" : "{}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(valid ? 200 : 401, response.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
          }
        });
    stub.start();
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  private ApiKeyExchangeFilter filter() {
    return new ApiKeyExchangeFilter(
        RestClient.builder().build(),
        new EdgeIdentityProperties(
            URI.create("http://127.0.0.1:" + stub.getAddress().getPort()),
            "orazaka-test-secret-at-least-32-characters!"));
  }

  @Test
  @DisplayName("A valid oz_ key is exchanged live on every request and the header rewritten")
  void validKeyExchangedPerRequest() throws Exception {
    ApiKeyExchangeFilter filter = filter();
    exchangeHits.set(0);
    AtomicReference<String> forwarded = new AtomicReference<>();

    for (int i = 0; i < 2; i++) {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
      request.addHeader("Authorization", "Bearer oz_valid_key");
      MockHttpServletResponse response = new MockHttpServletResponse();
      MockFilterChain chain =
          new MockFilterChain(
              new jakarta.servlet.http.HttpServlet() {
                @Override
                protected void service(
                    jakarta.servlet.http.HttpServletRequest req,
                    jakarta.servlet.http.HttpServletResponse res) {
                  forwarded.set(((HttpServletRequest) req).getHeader("Authorization"));
                }
              });
      filter.doFilter(request, response, chain);
      assertThat(response.getStatus()).isEqualTo(200);
    }

    assertThat(forwarded.get()).isEqualTo("Bearer minted-jwt");
    assertThat(exchangeHits.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("Non-oz_ bearers pass through untouched")
  void jwtPassesThrough() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
    request.addHeader("Authorization", "Bearer some.session.jwt");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> forwarded = new AtomicReference<>();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest req,
                  jakarta.servlet.http.HttpServletResponse res) {
                forwarded.set(((HttpServletRequest) req).getHeader("Authorization"));
              }
            });

    filter().doFilter(request, response, chain);

    assertThat(forwarded.get()).isEqualTo("Bearer some.session.jwt");
  }

  @Test
  @DisplayName("An invalid oz_ key is rejected with 401 at the facade")
  void invalidKeyRejected() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
    request.addHeader("Authorization", "Bearer oz_bogus");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter().doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("Invalid API key");
  }

  @Test
  @DisplayName("A key scoped ai:embed reaches POST /api/v1/embeddings with the minted JWT")
  void scopedKeyReachesItsEndpoint() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/embeddings");
    request.addHeader("Authorization", "Bearer oz_scoped_key");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> forwarded = new AtomicReference<>();
    MockFilterChain chain =
        new MockFilterChain(
            new jakarta.servlet.http.HttpServlet() {
              @Override
              protected void service(
                  jakarta.servlet.http.HttpServletRequest req,
                  jakarta.servlet.http.HttpServletResponse res) {
                forwarded.set(((HttpServletRequest) req).getHeader("Authorization"));
              }
            });

    filter().doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(forwarded.get()).isEqualTo("Bearer minted-jwt");
  }

  @Test
  @DisplayName("A scoped key is refused 403 anywhere else — the owner's JWT is never forwarded")
  void scopedKeyRefusedElsewhere() throws Exception {
    for (String[] call :
        new String[][] {
          {"GET", "/api/v1/embeddings"},
          {"POST", "/api/v1/intent"},
          {"POST", "/api/v1/embeddings/../chat"},
          {"GET", "/api/v1/api-keys"},
          {"DELETE", "/api/v1/profile"}
        }) {
      MockHttpServletRequest request = new MockHttpServletRequest(call[0], call[1]);
      request.addHeader("Authorization", "Bearer oz_scoped_key");
      MockHttpServletResponse response = new MockHttpServletResponse();
      AtomicReference<String> forwarded = new AtomicReference<>();
      MockFilterChain chain =
          new MockFilterChain(
              new jakarta.servlet.http.HttpServlet() {
                @Override
                protected void service(
                    jakarta.servlet.http.HttpServletRequest req,
                    jakarta.servlet.http.HttpServletResponse res) {
                  forwarded.set("reached");
                }
              });

      filter().doFilter(request, response, chain);

      assertThat(response.getStatus()).as(call[0] + " " + call[1]).isEqualTo(403);
      assertThat(forwarded.get()).isNull();
    }
  }

  @Test
  @DisplayName(
      "reco:sync feeds the recommender, reco:read reads it — and neither reaches anything else")
  void recommendationScopes() {
    java.util.List<String> sync = java.util.List.of("reco:sync");
    java.util.List<String> read = java.util.List.of("reco:read");
    assertThat(ApiKeyScopes.permits(sync, "POST", "/api/v1/recommendations/events")).isTrue();
    assertThat(ApiKeyScopes.permits(sync, "POST", "/api/v1/recommendations/items")).isTrue();
    assertThat(ApiKeyScopes.permits(sync, "POST", "/api/v1/recommendations/erasures")).isTrue();
    assertThat(ApiKeyScopes.permits(sync, "GET", "/api/v1/recommendations/for-you")).isFalse();
    assertThat(ApiKeyScopes.permits(read, "GET", "/api/v1/recommendations/for-you")).isTrue();
    assertThat(ApiKeyScopes.permits(read, "GET", "/api/v1/recommendations/similar")).isTrue();
    assertThat(ApiKeyScopes.permits(read, "GET", "/api/v1/recommendations/insights")).isTrue();
    assertThat(ApiKeyScopes.permits(read, "POST", "/api/v1/recommendations/events")).isFalse();
    assertThat(ApiKeyScopes.permits(read, "GET", "/api/v1/jobs")).isFalse();
    java.util.List<String> product = java.util.List.of("ai:embed", "reco:sync", "reco:read");
    assertThat(ApiKeyScopes.permits(product, "POST", "/api/v1/embeddings")).isTrue();
    assertThat(ApiKeyScopes.permits(product, "GET", "/api/v1/recommendations/for-you")).isTrue();
    assertThat(ApiKeyScopes.permits(product, "POST", "/api/v1/chat")).isFalse();
  }

  @Test
  @DisplayName(
      "analytics:sync sends product events, analytics:read reads insights (ADR-081) — nothing else")
  void analyticsScopes() {
    java.util.List<String> sync = java.util.List.of("analytics:sync");
    java.util.List<String> read = java.util.List.of("analytics:read");
    assertThat(ApiKeyScopes.permits(sync, "POST", "/api/v1/analytics/events")).isTrue();
    assertThat(ApiKeyScopes.permits(sync, "POST", "/api/v1/analytics/erasures")).isTrue();
    assertThat(ApiKeyScopes.permits(sync, "GET", "/api/v1/analytics/insights")).isFalse();
    assertThat(ApiKeyScopes.permits(read, "GET", "/api/v1/analytics/insights")).isTrue();
    assertThat(ApiKeyScopes.permits(read, "GET", "/api/v1/analytics/funnel")).isTrue();
    assertThat(ApiKeyScopes.permits(read, "POST", "/api/v1/analytics/events")).isFalse();
    assertThat(ApiKeyScopes.permits(read, "GET", "/api/v1/recommendations/for-you")).isFalse();
    assertThat(ApiKeyScopes.permits(sync, "POST", "/api/v1/recommendations/events")).isFalse();
  }

  @Test
  @DisplayName("A scope the edge does not know grants nothing; no scope keeps the owner's rights")
  void unknownScopeFailsClosedAndNoScopeIsUnrestricted() {
    assertThat(ApiKeyScopes.permits(java.util.List.of("ai:rerank"), "POST", "/api/v1/embeddings"))
        .isFalse();
    assertThat(ApiKeyScopes.permits(java.util.List.of(), "GET", "/api/v1/jobs")).isTrue();
    assertThat(ApiKeyScopes.permits(null, "GET", "/api/v1/jobs")).isTrue();
  }

  @Test
  @DisplayName("A key over its daily quota is refused 429 at the facade; another key is not")
  void keyOverItsDailyQuotaIsRefused() throws Exception {
    ApiKeyExchangeFilter filter =
        new ApiKeyExchangeFilter(
            RestClient.builder().build(),
            new EdgeIdentityProperties(
                URI.create("http://127.0.0.1:" + stub.getAddress().getPort()),
                "orazaka-test-secret-at-least-32-characters!"),
            new ApiKeyDailyQuota(2, java.time.Clock.systemUTC()));
    int[] statuses = new int[4];
    String[] keys = {"oz_valid_a", "oz_valid_a", "oz_valid_a", "oz_valid_b"};
    for (int i = 0; i < keys.length; i++) {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat");
      request.addHeader("Authorization", "Bearer " + keys[i]);
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, new MockFilterChain());
      statuses[i] = response.getStatus();
    }
    assertThat(statuses).containsExactly(200, 200, 429, 200);
  }
}
