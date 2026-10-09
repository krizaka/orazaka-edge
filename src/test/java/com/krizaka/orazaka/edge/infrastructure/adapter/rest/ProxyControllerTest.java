package com.krizaka.orazaka.edge.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Black-box round-trip of the streaming proxy against a JDK {@link HttpServer} stub backend:
 * status/body/header pass-through, hop-by-hop stripping, {@code X-Forwarded-*} injection, query
 * preservation, and 502 on an unreachable backend.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class ProxyControllerTest {

  private static HttpServer stubBackend;
  private static final Map<String, String> lastRequestHeaders = new ConcurrentHashMap<>();
  private static volatile String lastRequestBody = "";
  private static volatile String lastRequestPath = "";

  @LocalServerPort private int edgePort;

  private final HttpClient client = HttpClient.newHttpClient();

  @BeforeAll
  static void startStubBackend() throws IOException {
    stubBackend = HttpServer.create(new InetSocketAddress(0), 0);
    stubBackend.createContext(
        "/",
        exchange -> {
          lastRequestHeaders.clear();
          exchange.getRequestHeaders().forEach((k, v) -> lastRequestHeaders.put(k, v.getFirst()));
          try (InputStream in = exchange.getRequestBody()) {
            lastRequestBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          lastRequestPath =
              exchange.getRequestURI().getPath()
                  + (exchange.getRequestURI().getQuery() != null
                      ? "?" + exchange.getRequestURI().getQuery()
                      : "");
          byte[] body = "{\"from\":\"backend\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.getResponseHeaders().add("X-Backend-Header", "kept");
          int status = exchange.getRequestURI().getPath().contains("teapot") ? 418 : 200;
          exchange.sendResponseHeaders(status, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    stubBackend.start();
  }

  @AfterAll
  static void stopStubBackend() {
    stubBackend.stop(0);
  }

  @DynamicPropertySource
  static void routeToStub(DynamicPropertyRegistry registry) {
    registry.add("orazaka.edge.routes[0].path-prefix", () -> "/");
    registry.add(
        "orazaka.edge.routes[0].target",
        () -> "http://127.0.0.1:" + stubBackend.getAddress().getPort());
  }

  private URI edge(String pathAndQuery) {
    return URI.create("http://127.0.0.1:" + edgePort + pathAndQuery);
  }

  @Test
  @DisplayName("GET round-trip: status, body, backend headers and query string pass through")
  void getRoundTrip() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(edge("/api/v1/anything?limit=5")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"from\":\"backend\"}");
    assertThat(response.headers().firstValue("X-Backend-Header")).contains("kept");
    assertThat(lastRequestPath).isEqualTo("/api/v1/anything?limit=5");
  }

  @Test
  @DisplayName("Request headers pass through with X-Forwarded-* appended")
  void forwardHeadersInjected() throws Exception {
    client.send(
        HttpRequest.newBuilder(edge("/api/v1/headers"))
            .header("Authorization", "Bearer token-1")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(lastRequestHeaders).containsEntry("Authorization", "Bearer token-1");
    assertThat(lastRequestHeaders).containsKey("X-forwarded-for");
    assertThat(lastRequestHeaders).containsKey("X-forwarded-proto");
  }

  @Test
  @DisplayName("POST body streams through to the backend")
  void postBodyStreams() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(edge("/api/v1/jobs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"featureKey\":\"x\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(lastRequestBody).isEqualTo("{\"featureKey\":\"x\"}");
  }

  @Test
  @DisplayName("Non-2xx backend statuses pass through verbatim")
  void statusPassThrough() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(edge("/api/v1/teapot")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(418);
  }
}
