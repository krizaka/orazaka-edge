package com.krizaka.orazaka.edge.infrastructure.config.filter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class LazyRedisConnectionTest {

  @SuppressWarnings("unchecked")
  private static StatefulRedisConnection<String, String> open() {
    return (StatefulRedisConnection<String, String>)
        Proxy.newProxyInstance(
            LazyRedisConnectionTest.class.getClassLoader(),
            new Class<?>[] {StatefulRedisConnection.class},
            (proxy, method, args) -> null);
  }

  @Test
  void anOpenConnectionIsGivenBackAsItIs() {
    StatefulRedisConnection<String, String> connection = open();

    assertSame(connection, new LazyRedisConnection(connection).get());
  }

  @Test
  void aMissingConnectionOrClientIsRefusedAtOnce() {
    assertThrows(
        NullPointerException.class,
        () -> new LazyRedisConnection((StatefulRedisConnection<String, String>) null));
    assertThrows(NullPointerException.class, () -> new LazyRedisConnection((RedisClient) null));
    assertNotNull(open());
  }
}
