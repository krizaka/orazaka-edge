package com.orazaka.edge.infrastructure.adapter.rest;

import com.orazaka.edge.infrastructure.support.RouteResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Streaming reverse proxy of the edge gateway: forwards every request to the backend resolved by
 * the route table and streams the response back chunk-by-chunk.
 *
 * <p>The per-chunk {@code flushBuffer()} on a virtual thread is the SSE pass-through:
 * token-by-token frames flow unbuffered for {@code text/event-stream} responses, so interactive
 * chat streaming stays synchronous through the edge (AGENTS.md §6 rule survives the migration).
 *
 * <p>Transport only: hop-by-hop headers are stripped, {@code X-Forwarded-*} is appended, status and
 * body pass through verbatim — authorization stays with the backend that owns the resource.
 */
@RestController
public class ProxyController {

  private static final Logger logger = LoggerFactory.getLogger(ProxyController.class);

  /** Hop-by-hop request/response headers that must not be forwarded (RFC 9110 §7.6.1). */
  private static final Set<String> HOP_BY_HOP_HEADERS =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "expect",
          "host",
          "content-length");

  private static final int STREAM_BUFFER_BYTES = 8192;

  private final RestClient proxyRestClient;
  private final RouteResolver routeResolver;

  public ProxyController(RestClient proxyRestClient, RouteResolver routeResolver) {
    this.proxyRestClient = Objects.requireNonNull(proxyRestClient, "RestClient cannot be null");
    this.routeResolver = Objects.requireNonNull(routeResolver, "RouteResolver cannot be null");
  }

  /** Catch-all proxy entry point — everything not served by the edge itself is forwarded. */
  @RequestMapping("/**")
  public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
    URI target = buildTargetUri(request);
    try {
      RestClient.RequestBodySpec spec =
          proxyRestClient
              .method(HttpMethod.valueOf(request.getMethod()))
              .uri(target)
              .headers(headers -> copyRequestHeaders(request, headers::add));
      if (hasBody(request)) {
        spec.body(outputStream -> request.getInputStream().transferTo(outputStream));
      }
      spec.exchange(
          (clientRequest, clientResponse) -> {
            relayResponse(clientResponse, response);
            return null;
          });
    } catch (ResourceAccessException e) {
      logger.warn("Backend unreachable for {} {}: {}", request.getMethod(), target, e.getMessage());
      if (!response.isCommitted()) {
        response.setStatus(502);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"Bad Gateway\"}");
      }
    } catch (UncheckedIOException e) {
      // Client disconnected mid-stream (SSE tab closed) — nothing left to relay.
      logger.debug("Client stream closed for {} {}", request.getMethod(), target);
    }
  }

  private URI buildTargetUri(HttpServletRequest request) {
    URI backend = routeResolver.resolve(request.getRequestURI());
    String query = request.getQueryString();
    String pathAndQuery = request.getRequestURI() + (query != null ? "?" + query : "");
    return URI.create(backend.toString().replaceAll("/$", "") + pathAndQuery);
  }

  private void copyRequestHeaders(HttpServletRequest request, HeaderSink sink) {
    for (String name : Collections.list(request.getHeaderNames())) {
      if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        continue;
      }
      for (String value : Collections.list(request.getHeaders(name))) {
        sink.add(name, value);
      }
    }
    String existingForwardedFor = request.getHeader("X-Forwarded-For");
    sink.add(
        "X-Forwarded-For",
        existingForwardedFor != null
            ? existingForwardedFor + ", " + request.getRemoteAddr()
            : request.getRemoteAddr());
    sink.add("X-Forwarded-Proto", request.getScheme());
    sink.add("X-Forwarded-Host", request.getServerName() + ":" + request.getServerPort());
  }

  private void relayResponse(
      org.springframework.http.client.ClientHttpResponse clientResponse,
      HttpServletResponse response)
      throws IOException {
    response.setStatus(clientResponse.getStatusCode().value());
    clientResponse
        .getHeaders()
        .forEach(
            (name, values) -> {
              if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> response.addHeader(name, value));
              }
            });
    try (InputStream in = clientResponse.getBody()) {
      OutputStream out = response.getOutputStream();
      byte[] buffer = new byte[STREAM_BUFFER_BYTES];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
        // Unbuffered relay: each upstream chunk (an SSE frame, a token) reaches the
        // client immediately instead of waiting for the response buffer to fill.
        response.flushBuffer();
      }
    }
  }

  private static boolean hasBody(HttpServletRequest request) {
    return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
  }

  /** Local functional sink so header copying stays testable without Spring types. */
  @FunctionalInterface
  interface HeaderSink {
    void add(String name, String value);
  }
}
