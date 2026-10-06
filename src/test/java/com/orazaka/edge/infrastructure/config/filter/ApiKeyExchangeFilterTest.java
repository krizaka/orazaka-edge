package com.orazaka.edge.infrastructure.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.orazaka.edge.infrastructure.config.EdgeIdentityProperties;
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
          boolean valid = body.contains("oz_valid");
          byte[] response =
              (valid ? "{\"token\":\"minted-jwt\"}" : "{}").getBytes(StandardCharsets.UTF_8);
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
}
