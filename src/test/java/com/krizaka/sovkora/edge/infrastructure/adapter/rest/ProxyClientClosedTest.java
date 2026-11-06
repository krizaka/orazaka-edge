package com.krizaka.sovkora.edge.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.krizaka.sovkora.edge.infrastructure.config.EdgeRoutesProperties;
import com.krizaka.sovkora.edge.infrastructure.support.RouteResolver;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;

/**
 * krizaka-internal#2597: a browser that closes the jobs stream (Broken pipe while the edge flushes
 * to it) is the client leaving, not the backend being unreachable. The backend log line must say
 * what happened, at DEBUG; "Backend unreachable" stays for a real connection failure.
 */
class ProxyClientClosedTest {

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger proxyLogger = (Logger) LoggerFactory.getLogger(ProxyController.class);
  private Level previousLevel;
  private HttpServer backend;

  @BeforeEach
  void captureLogs() {
    previousLevel = proxyLogger.getLevel();
    proxyLogger.setLevel(Level.DEBUG);
    logs.start();
    proxyLogger.addAppender(logs);
  }

  @AfterEach
  void restore() {
    proxyLogger.detachAppender(logs);
    proxyLogger.setLevel(previousLevel);
    if (backend != null) {
      backend.stop(0);
    }
  }

  @Test
  @DisplayName("A client that closes the stream is logged once at DEBUG, never as unreachable")
  void clientClosedIsNotBackendUnreachable() throws Exception {
    URI base = startBackend();
    ProxyController controller = controllerFor(base);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs/stream");
    request.setRequestURI("/api/v1/jobs/stream");

    controller.proxy(request, new BrokenPipeResponse());

    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(message -> message.contains("Backend unreachable"));
    assertThat(logs.list)
        .filteredOn(event -> event.getFormattedMessage().contains("client closed the stream"))
        .singleElement()
        .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
  }

  @Test
  @DisplayName("A real connection failure to the backend is still logged as unreachable (WARN)")
  void realConnectionFailureIsStillUnreachable() throws Exception {
    URI base = startBackend();
    backend.stop(0);
    backend = null;
    ProxyController controller = controllerFor(base);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs/stream");
    request.setRequestURI("/api/v1/jobs/stream");
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.proxy(request, response);

    assertThat(response.getStatus()).isEqualTo(502);
    assertThat(logs.list)
        .filteredOn(event -> event.getFormattedMessage().contains("Backend unreachable"))
        .singleElement()
        .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
  }

  private URI startBackend() throws IOException {
    backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    backend.createContext(
        "/",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write("data: tick\n\n".getBytes(StandardCharsets.UTF_8));
          }
        });
    backend.start();
    return URI.create("http://127.0.0.1:" + backend.getAddress().getPort());
  }

  private static ProxyController controllerFor(URI base) {
    RouteResolver resolver =
        new RouteResolver(
            new EdgeRoutesProperties(List.of(new EdgeRoutesProperties.Route("/", base))));
    return new ProxyController(RestClient.builder().build(), resolver);
  }

  /** A response whose client has gone: every write fails like a Broken pipe. */
  private static final class BrokenPipeResponse extends MockHttpServletResponse {
    @Override
    public ServletOutputStream getOutputStream() {
      return new ServletOutputStream() {
        @Override
        public void write(int b) throws IOException {
          throw new IOException("Broken pipe");
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
          // synchronous test double
        }
      };
    }
  }
}
