package com.krizaka.orazaka.edge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;

@SpringBootTest
class EdgeApplicationTest {

  @Autowired private ApplicationContext context;

  @Test
  @DisplayName("The edge context boots with the proxy client and route table wired")
  void contextLoads() {
    assertThat(context.getBean(RestClient.class)).isNotNull();
    assertThat(context.containsBean("proxyController")).isTrue();
  }
}
