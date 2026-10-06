package com.orazaka.edge.infrastructure.support;

import com.orazaka.edge.infrastructure.config.EdgeRoutesProperties;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Longest-prefix route matcher over the edge route table. Stateless — the table is immutable
 * config; canary/cutover changes arrive as config changes, not code.
 */
@Component
public class RouteResolver {

  private final List<EdgeRoutesProperties.Route> routesByLongestPrefix;

  public RouteResolver(EdgeRoutesProperties properties) {
    Objects.requireNonNull(properties, "EdgeRoutesProperties cannot be null");
    this.routesByLongestPrefix =
        properties.routes().stream()
            .sorted(
                Comparator.comparingInt(
                        (EdgeRoutesProperties.Route route) -> route.pathPrefix().length())
                    .reversed())
            .toList();
  }

  /**
   * Resolves the backend base URL for a request path (longest matching prefix wins).
   *
   * @param path the request path (e.g. {@code /api/v1/jobs/42})
   * @return the target backend base URL
   * @throws IllegalStateException if no route matches (a '/' catch-all prevents this)
   */
  public URI resolve(String path) {
    Objects.requireNonNull(path, "path cannot be null");
    return routesByLongestPrefix.stream()
        .filter(route -> path.startsWith(route.pathPrefix()))
        .map(EdgeRoutesProperties.Route::target)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No edge route matches path: " + path));
  }
}
