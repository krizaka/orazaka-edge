package com.krizaka.orazaka.edge.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
    registry.add("orazaka.edge.routes[1].path-prefix", () -> "/down");
    registry.add("orazaka.edge.routes[1].target", () -> "http://127.0.0.1:" + DEAD_PORT);
  }

  /** A port nothing listens on: a backend that is down. */
  private static final int DEAD_PORT = closedPort();

  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
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

  @Test
  @DisplayName(
      "A signed webhook crosses the edge untouched: same bytes, same webhook-* headers, no credential added (ADR-084)")
  void signedWebhookIsForwardedVerbatim() throws Exception {
    // The signature covers the body byte for byte: multi-byte characters and trailing whitespace
    // must survive.
    String body = "{\"note\":\"réservé — 100 %\",\"n\":[1, 2]}  \n";
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(edge("/hooks/6f1c2b1e-0000-4000-8000-000000000001"))
                .header("Content-Type", "application/json")
                .header("webhook-id", "msg_p5jXN8AQM9LWM0D4loKWxJek")
                .header("webhook-timestamp", "1614265330")
                .header("webhook-signature", "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(lastRequestPath).isEqualTo("/hooks/6f1c2b1e-0000-4000-8000-000000000001");
    assertThat(lastRequestBody).isEqualTo(body);
    assertThat(lastRequestHeaders)
        .containsEntry("Webhook-id", "msg_p5jXN8AQM9LWM0D4loKWxJek")
        .containsEntry("Webhook-timestamp", "1614265330")
        .containsEntry("Webhook-signature", "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    assertThat(lastRequestHeaders)
        .as("the edge authenticates nothing here and adds no credential")
        .doesNotContainKey("Authorization");
  }

  @Test
  @DisplayName(
      "An unreachable backend answers 502 as RFC 9457 problem details (and keeps the legacy error member)")
  void unreachableBackendIsProblemDetails() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(edge("/down/api/v1/anything")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(502);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
    assertThat(response.body())
        .contains("\"type\":\"about:blank\"")
        .contains("\"title\":\"Bad Gateway\"")
        .contains("\"status\":502")
        .contains("\"detail\":")
        .contains("\"error\":\"Bad Gateway\"");
  }

  // ADR-085 slice 1: one request id, end to end.

  @Test
  @DisplayName(
      "A request without an id gets one: forwarded to the backend as X-Request-Id and echoed to the caller")
  void requestIdIsGeneratedForwardedAndEchoed() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(edge("/api/v1/anything")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    String forwarded = lastRequestHeaders.get("X-request-id");
    assertThat(forwarded).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    assertThat(response.headers().firstValue("X-Request-Id")).contains(forwarded);
  }

  @Test
  @DisplayName(
      "A well-formed id sent by the caller (or the platform's ingress) is kept, never replaced")
  void wellFormedRequestIdIsKept() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(edge("/api/v1/anything"))
                .header("X-Request-Id", "9f1c2d3e-aaaa-bbbb-cccc-123456789abc")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(lastRequestHeaders)
        .containsEntry("X-request-id", "9f1c2d3e-aaaa-bbbb-cccc-123456789abc");
    assertThat(response.headers().firstValue("X-Request-Id"))
        .contains("9f1c2d3e-aaaa-bbbb-cccc-123456789abc");
  }

  @Test
  @DisplayName("A malformed id (log injection, too long) is replaced by a fresh one, once")
  void malformedRequestIdIsReplaced() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(edge("/api/v1/anything"))
                .header("X-Request-Id", "x".repeat(200))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    String forwarded = lastRequestHeaders.get("X-request-id");
    assertThat(forwarded).hasSize(36);
    assertThat(response.headers().allValues("X-Request-Id")).containsExactly(forwarded);
  }
}
