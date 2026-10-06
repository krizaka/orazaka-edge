package com.orazaka.edge.infrastructure.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP client of the streaming proxy: {@link RestClient} over the Spring-managed JDK
 * {@link HttpClient} factory (AGENTS.md §4 — RestClient only, no hand-rolled HTTP client).
 *
 * <p>No read timeout on purpose: SSE streams (chat tokens, job progress) stay open for the whole
 * interaction; a parked virtual thread per open stream is the §4 concurrency model. Connect
 * failures surface fast (5s) and map to 502 at the proxy.
 */
@Configuration
class ProxyClientConfig {

  @Bean
  RestClient proxyRestClient() {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    return RestClient.builder().requestFactory(requestFactory).build();
  }
}
