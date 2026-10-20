package com.krizaka.orazaka.edge.infrastructure.config.filter;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.Objects;

/**
 * Buckets in Redis, shared by every edge instance (ADR-083 slice 3). One atomic Lua script per
 * call: it refills the bucket from the elapsed time, takes a token if there is one and answers; the
 * time is <b>Redis's own clock</b> ({@code TIME}), so instances whose clocks differ still agree. A
 * bucket the clients stop using expires by itself (two minutes, longer than any bucket needs to
 * refill), so the store never fills up. The limit is the caller's at every call: an operator who
 * raises a tier is obeyed on the next request, as with the in-memory store.
 */
final class RedisBucketStore implements BucketStore {

  /** The key prefix: one hash per digest. */
  static final String PREFIX = "kz:edge:rl:";

  private static final long TTL_MILLIS = 120_000L;

  private static final String SCRIPT =
      """
      local limit = tonumber(ARGV[1])
      local ttl = tonumber(ARGV[2])
      local t = redis.call('TIME')
      local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
      local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
      local tokens = tonumber(data[1])
      local ts = tonumber(data[2])
      if tokens == nil then
        tokens = limit
        ts = now
      end
      local elapsed = math.max(0, now - ts)
      tokens = math.min(limit, tokens + elapsed * limit / 60000)
      local allowed = 0
      if tokens >= 1 - 1e-9 then
        tokens = math.max(0, tokens - 1)
        allowed = 1
      end
      redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
      redis.call('PEXPIRE', KEYS[1], ttl)
      local retry = 0
      if allowed == 0 then
        retry = math.max(1, math.ceil(((1 - tokens) * 60000 / limit) / 1000 - 1e-9))
      end
      return {allowed, math.floor(tokens + 1e-9), retry}
      """;

  private final java.util.function.Supplier<StatefulRedisConnection<String, String>> connections;
  private volatile StatefulRedisConnection<String, String> connection;

  /**
   * A store on an open connection (Lettuce reconnects it by itself after a drop).
   *
   * @param connection the connection
   */
  RedisBucketStore(StatefulRedisConnection<String, String> connection) {
    Objects.requireNonNull(connection, "connection cannot be null");
    this.connection = connection;
    this.connections = () -> connection;
  }

  /**
   * A store that connects on first use and again after a failed connection, so a Redis that is down
   * at start-up does not stop the edge from starting.
   *
   * @param client the client
   */
  RedisBucketStore(RedisClient client) {
    Objects.requireNonNull(client, "client cannot be null");
    this.connections = client::connect;
  }

  private StatefulRedisConnection<String, String> connected() {
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

  @Override
  public ApiKeyRateLimiter.Decision take(String digest, int limit) {
    List<Object> answer =
        connected()
            .sync()
            .eval(
                SCRIPT,
                ScriptOutputType.MULTI,
                new String[] {PREFIX + digest},
                Integer.toString(limit),
                Long.toString(TTL_MILLIS));
    boolean allowed = ((Long) answer.get(0)) == 1L;
    int remaining = Math.toIntExact((Long) answer.get(1));
    long retryAfter = (Long) answer.get(2);
    return allowed
        ? new ApiKeyRateLimiter.Decision(true, false, limit, remaining, 0)
        : new ApiKeyRateLimiter.Decision(false, true, limit, 0, retryAfter);
  }

  @Override
  public int trackedKeys() {
    return 0;
  }
}
