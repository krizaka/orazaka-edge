package com.orazaka.edge.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.orazaka.edge.infrastructure.config.EdgeRoutesProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RouteResolverTest {

  private static final URI ROUTER = URI.create("http://backend-router:8080");
  private static final URI IDENTITY = URI.create("http://backend-identity:8083");

  @Test
  @DisplayName("The longest matching prefix wins")
  void longestPrefixWins() {
    RouteResolver resolver =
        new RouteResolver(
            new EdgeRoutesProperties(
                List.of(
                    new EdgeRoutesProperties.Route("/", ROUTER),
                    new EdgeRoutesProperties.Route("/api/v1/auth", IDENTITY))));

    assertThat(resolver.resolve("/api/v1/auth/login")).isEqualTo(IDENTITY);
    assertThat(resolver.resolve("/api/v1/jobs")).isEqualTo(ROUTER);
    assertThat(resolver.resolve("/actuator/health")).isEqualTo(ROUTER);
  }

  @Test
  @DisplayName("Declaration order does not matter — matching is by prefix length")
  void declarationOrderIrrelevant() {
    RouteResolver resolver =
        new RouteResolver(
            new EdgeRoutesProperties(
                List.of(
                    new EdgeRoutesProperties.Route("/api/v1/auth", IDENTITY),
                    new EdgeRoutesProperties.Route("/", ROUTER))));

    assertThat(resolver.resolve("/api/v1/auth/login")).isEqualTo(IDENTITY);
    assertThat(resolver.resolve("/uploads/a.png")).isEqualTo(ROUTER);
  }

  @Test
  @DisplayName("Without a catch-all, an unmatched path fails fast")
  void unmatchedPathFailsFast() {
    RouteResolver resolver =
        new RouteResolver(
            new EdgeRoutesProperties(
                List.of(new EdgeRoutesProperties.Route("/api/v1/auth", IDENTITY))));

    assertThatIllegalStateException().isThrownBy(() -> resolver.resolve("/api/v1/jobs"));
  }
}
