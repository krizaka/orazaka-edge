package com.krizaka.orazaka.edge.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The route table the edge ships (application.yml), bound the way the running edge binds it. */
class EdgeRouteTableTest {

  private static EdgeRoutesProperties shipped() throws IOException {
    PropertySource<?> yaml =
        new YamlPropertySourceLoader()
            .load("edge", new ClassPathResource("application.yml"))
            .getFirst();
    var sources = new MutablePropertySources();
    sources.addFirst(yaml);
    return new Binder(
            ConfigurationPropertySources.from(sources),
            new org.springframework.boot.context.properties.bind
                .PropertySourcesPlaceholdersResolver(sources))
        .bind("orazaka.edge", EdgeRoutesProperties.class)
        .get();
  }

  private static String targetOf(EdgeRoutesProperties routes, String path) {
    return routes.routes().stream()
        .filter(r -> path.startsWith(r.pathPrefix()))
        .max(java.util.Comparator.comparingInt(r -> r.pathPrefix().length()))
        .orElseThrow()
        .target()
        .toString();
  }

  @Test
  @DisplayName(
      "The account administration of the console reaches the identity service, not the catch-all")
  void adminUsersGoesToIdentity() throws IOException {
    EdgeRoutesProperties routes = shipped();
    assertThat(targetOf(routes, "/api/v1/admin/users")).isEqualTo("http://localhost:8083");
    assertThat(targetOf(routes, "/api/v1/admin/users/abc/suspend"))
        .isEqualTo("http://localhost:8083");
    assertThat(targetOf(routes, "/api/v1/features/all")).isEqualTo("http://localhost:8080");
  }

  @Test
  @DisplayName(
      "The organizations (members, service accounts, their keys) reach the identity service")
  void organizationsGoToIdentity() throws IOException {
    EdgeRoutesProperties routes = shipped();
    assertThat(targetOf(routes, "/api/v1/organizations")).isEqualTo("http://localhost:8083");
    assertThat(targetOf(routes, "/api/v1/organizations/org-krizaka/service-keys"))
        .isEqualTo("http://localhost:8083");
  }
}
