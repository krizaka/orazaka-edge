package com.krizaka.orazaka.edge.infrastructure.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One request id, end to end (ADR-085, slice 1): the id the caller or the platform's ingress
 * already gave the request ({@code X-Request-Id}) when it is well-formed, else a fresh UUID. It is
 * put in the logging context ({@code requestId}), echoed on the response and handed to {@code
 * ProxyController}, which forwards it to the backend. A malformed value (too long, a character a
 * log line must not carry) is replaced, never forwarded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  /** The request attribute holding the id the rest of the request uses. */
  public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

  public static final String HEADER = "X-Request-Id";
  public static final String MDC_KEY = "requestId";

  private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String id =
        incoming != null && WELL_FORMED.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, id);
    response.setHeader(HEADER, id);
    MDC.put(MDC_KEY, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
