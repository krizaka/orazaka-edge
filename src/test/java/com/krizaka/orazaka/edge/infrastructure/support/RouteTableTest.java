package com.krizaka.orazaka.edge.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.edge.infrastructure.config.EdgeRoutesProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The route table the edge ships with (application.yml), resolved as deployed with its defaults.
 */
class RouteTableTest {

  private static RouteResolver shipped() throws Exception {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    Binder binder =
        new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources));
    return new RouteResolver(binder.bind("orazaka.edge", EdgeRoutesProperties.class).get());
  }

  @Test
  @DisplayName(
      "/api/v1/recommendations reaches the knowledge service (ADR-078); the rest is unchanged")
  void recommendationsGoToTheKnowledgeService() throws Exception {
    RouteResolver routes = shipped();
    assertThat(routes.resolve("/api/v1/recommendations/for-you"))
        .isEqualTo(URI.create("http://localhost:8084"));
    assertThat(routes.resolve("/api/v1/studios")).isEqualTo(URI.create("http://localhost:8096"));
    assertThat(routes.resolve("/api/v1/embeddings")).isEqualTo(URI.create("http://localhost:8080"));
  }

  @Test
  @DisplayName("/api/v1/analytics reaches the knowledge service (ADR-081)")
  void analyticsGoesToTheKnowledgeService() throws Exception {
    assertThat(shipped().resolve("/api/v1/analytics/insights"))
        .isEqualTo(URI.create("http://localhost:8084"));
  }

  @Test
  @DisplayName(
      "/api/v1/automations and the signed webhook /hooks reach the automation service (ADR-084)")
  void automationsGoToTheAutomationService() throws Exception {
    RouteResolver routes = shipped();
    URI automation = URI.create("http://localhost:8091");
    assertThat(routes.resolve("/api/v1/automations")).isEqualTo(automation);
    assertThat(routes.resolve("/api/v1/automations/6f1c2b1e-0000-4000-8000-000000000001/triggers"))
        .isEqualTo(automation);
    assertThat(routes.resolve("/hooks/6f1c2b1e-0000-4000-8000-000000000001")).isEqualTo(automation);
  }

  @Test
  @DisplayName(
      "the automation service's internal surface is never routed: /internal stays on the catch-all")
  void internalIsNotRoutedToAutomation() throws Exception {
    assertThat(shipped().resolve("/internal/v1/anything"))
        .as("only the '/' catch-all, never the automation service")
        .isNotEqualTo(URI.create("http://localhost:8091"));
  }
}
