package com.krizaka.orazaka.edge.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EdgeIdentityPropertiesTest {

  @Test
  @DisplayName("Valid wiring is accepted")
  void validWiring() {
    var properties =
        new EdgeIdentityProperties(
            URI.create("http://identity:8083"), "orazaka-test-secret-at-least-32-characters!");
    assertThat(properties.baseUrl()).isEqualTo(URI.create("http://identity:8083"));
  }

  @Test
  @DisplayName("Relative and null URLs are rejected")
  void invalidWiringRejected() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new EdgeIdentityProperties(null, "orazaka-test-secret-at-least-32-characters!"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new EdgeIdentityProperties(
                    URI.create("/relative"), "orazaka-test-secret-at-least-32-characters!"));
  }
}
