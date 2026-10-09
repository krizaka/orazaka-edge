package com.krizaka.orazaka.edge.infrastructure.config;

import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Wiring of the identity service consumed by the edge ({@code orazaka.edge.identity}): the {@code
 * oz_} API-key exchange endpoint. Exchanges are deliberately uncached so key revocation takes
 * effect immediately.
 *
 * @param baseUrl the identity service base URL
 */
@ConfigurationProperties(prefix = "orazaka.edge.identity")
public record EdgeIdentityProperties(URI baseUrl, String serviceSecret) {

  public EdgeIdentityProperties {
    Objects.requireNonNull(baseUrl, "identity base-url is required");
    if (baseUrl.getHost() == null) {
      throw new IllegalArgumentException("identity base-url must be absolute: " + baseUrl);
    }
  }
}
