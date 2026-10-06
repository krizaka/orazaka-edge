package com.orazaka.edge.infrastructure.config;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The edge route table ({@code orazaka.edge.routes}): path prefix → backend base URL.
 *
 * <p>This table IS the strangler facade — extracting a service is adding a more specific prefix
 * here (e.g. {@code /api/v1/auth} → identity service), and rolling back is reverting it. Longest
 * prefix wins (see {@code RouteResolver}).
 *
 * @param routes ordered route entries; at least one (the catch-all) is required
 */
@ConfigurationProperties(prefix = "orazaka.edge")
public record EdgeRoutesProperties(List<Route> routes) {

  public EdgeRoutesProperties {
    if (routes == null || routes.isEmpty()) {
      throw new IllegalArgumentException("orazaka.edge.routes must declare at least one route");
    }
    routes = List.copyOf(routes);
  }

  /**
   * One route-table entry.
   *
   * @param pathPrefix request path prefix this route matches (must start with '/')
   * @param target backend base URL the matched request is proxied to
   */
  public record Route(String pathPrefix, URI target) {

    public Route {
      if (pathPrefix == null || !pathPrefix.startsWith("/")) {
        throw new IllegalArgumentException("route path-prefix must start with '/'");
      }
      Objects.requireNonNull(target, "route target cannot be null");
      if (target.getHost() == null) {
        throw new IllegalArgumentException("route target must be an absolute URL: " + target);
      }
    }
  }
}
