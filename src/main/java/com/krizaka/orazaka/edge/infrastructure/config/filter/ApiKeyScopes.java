package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What each API-key scope allows through the facade. A key with no scope keeps the full rights of
 * its owner; a key with scopes may only make the requests its scopes name. The minted JWT is the
 * owner's, so this is the one place a scoped key is held to its scopes — and a scope the edge does
 * not know grants nothing (fail closed).
 */
final class ApiKeyScopes {

  /** One allowed request: an HTTP method and an exact path. */
  record Rule(String method, String path) {}

  private static final Map<String, List<Rule>> RULES =
      Map.of(
          // Texts to vectors with the platform's embedding model — nothing else.
          "ai:embed",
          List.of(new Rule("POST", "/api/v1/embeddings")),
          // A product feeding its recommender (ADR-078): events, eligible items, erasures.
          "reco:sync",
          List.of(
              new Rule("POST", "/api/v1/recommendations/events"),
              new Rule("POST", "/api/v1/recommendations/items"),
              new Rule("POST", "/api/v1/recommendations/erasures")),
          // A product reading its recommender: feeds and creator analytics.
          "reco:read",
          List.of(
              new Rule("GET", "/api/v1/recommendations/for-you"),
              new Rule("GET", "/api/v1/recommendations/similar"),
              new Rule("GET", "/api/v1/recommendations/insights")),
          // A product sending its own events to analytics, and forgetting people (ADR-081).
          "analytics:sync",
          List.of(
              new Rule("POST", "/api/v1/analytics/events"),
              new Rule("POST", "/api/v1/analytics/erasures")),
          // A product reading its analytics: KPIs, series, top items, cohorts, funnels.
          "analytics:read",
          List.of(
              new Rule("GET", "/api/v1/analytics/insights"),
              new Rule("GET", "/api/v1/analytics/funnel")));

  private ApiKeyScopes() {}

  /** {@code true} when the key may make this request. An empty scope set is unrestricted. */
  static boolean permits(Collection<String> scopes, String method, String path) {
    if (scopes == null || scopes.isEmpty()) {
      return true;
    }
    return scopes.stream()
        .flatMap(scope -> RULES.getOrDefault(scope, List.of()).stream())
        .anyMatch(rule -> rule.method().equalsIgnoreCase(method) && rule.path().equals(path));
  }
}
