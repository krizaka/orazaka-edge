package com.krizaka.orazaka.edge.infrastructure.config.filter;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Daily counts in Redis, shared by every edge (ADR-083 slice 3): {@code INCR} and, on the first
 * call of the day, an expiry an hour after the day ends — one atomic script, so a crash between the
 * two can never leave a counter that lives forever.
 */
final class RedisDailyCounterStore implements DailyCounterStore {

  private static final String PREFIX = "kz:edge:day:";
  private static final long GRACE_SECONDS = 3_600L;

  private static final String SCRIPT =
      """
      local count = redis.call('INCR', KEYS[1])
      if count == 1 then
        redis.call('EXPIREAT', KEYS[1], tonumber(ARGV[1]))
      end
      return count
      """;

  private final LazyRedisConnection redis;

  RedisDailyCounterStore(StatefulRedisConnection<String, String> connection) {
    this.redis = new LazyRedisConnection(connection);
  }

  RedisDailyCounterStore(RedisClient client) {
    this.redis = new LazyRedisConnection(client);
  }

  @Override
  public long increment(String digest, LocalDate day) {
    long expireAt = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() + GRACE_SECONDS;
    return (Long)
        redis
            .get()
            .sync()
            .eval(
                SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {PREFIX + day + ":" + digest},
                Long.toString(expireAt));
  }
}
