package com.orazaka.edge.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EdgeRoutesPropertiesTest {

  @Test
  @DisplayName("A valid route table is immutable")
  void validTableIsImmutable() {
    var properties =
        new EdgeRoutesProperties(
            List.of(new EdgeRoutesProperties.Route("/", URI.create("http://backend:8080"))));

    assertThat(properties.routes()).hasSize(1);
    assertThat(properties.routes().getFirst().pathPrefix()).isEqualTo("/");
  }

  @Test
  @DisplayName("An empty route table is rejected — the facade needs at least a catch-all")
  void emptyTableRejected() {
    assertThatIllegalArgumentException().isThrownBy(() -> new EdgeRoutesProperties(List.of()));
    assertThatIllegalArgumentException().isThrownBy(() -> new EdgeRoutesProperties(null));
  }

  @Test
  @DisplayName("Route prefixes must be absolute paths and targets absolute URLs")
  void invalidRoutesRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new EdgeRoutesProperties.Route("api", URI.create("http://backend:8080")));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new EdgeRoutesProperties.Route("/", URI.create("/relative")));
    assertThatNullPointerException().isThrownBy(() -> new EdgeRoutesProperties.Route("/", null));
  }
}
