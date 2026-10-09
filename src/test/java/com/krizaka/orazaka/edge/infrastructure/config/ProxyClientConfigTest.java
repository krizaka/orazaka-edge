package com.krizaka.orazaka.edge.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProxyClientConfigTest {

  @Test
  @DisplayName("Builds the streaming proxy client (JDK factory, no read timeout)")
  void buildsProxyClient() {
    assertThat(new ProxyClientConfig().proxyRestClient()).isNotNull();
  }
}
