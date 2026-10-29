package com.krizaka.orazaka.edge.infrastructure.config.filter;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The Redis connection of a store: given open, or opened on first use and again after a failed
 * attempt, so a Redis that is down at start-up does not stop the edge from starting (Lettuce
 * reconnects an open connection by itself after a drop).
 */
final class LazyRedisConnection {

  private final Supplier<StatefulRedisConnection<String, String>> connections;
  private volatile StatefulRedisConnection<String, String> connection;

  /**
   * A connection that is already open.
   *
   * @param open the connection
   */
  LazyRedisConnection(StatefulRedisConnection<String, String> open) {
    Objects.requireNonNull(open, "connection cannot be null");
    this.connection = open;
    this.connections = () -> open;
  }

  /**
   * A connection opened on first use.
   *
   * @param client the client
   */
  LazyRedisConnection(RedisClient client) {
    Objects.requireNonNull(client, "client cannot be null");
    this.connections = client::connect;
  }

  /**
   * The connection, opened if it is not yet.
   *
   * @return the open connection
   */
  StatefulRedisConnection<String, String> get() {
    StatefulRedisConnection<String, String> current = connection;
    if (current == null) {
      synchronized (this) {
        if (connection == null) {
          connection = connections.get();
        }
        current = connection;
      }
    }
    return current;
  }
}
