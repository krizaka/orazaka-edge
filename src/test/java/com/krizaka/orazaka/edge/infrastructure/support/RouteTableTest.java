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
}
