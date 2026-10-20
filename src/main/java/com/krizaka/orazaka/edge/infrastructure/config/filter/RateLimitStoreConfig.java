package com.krizaka.orazaka.edge.infrastructure.config.filter;

import io.lettuce.core.RedisClient;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where the limiter's state lives (ADR-083 slice 3): {@code ABUSE_RATE_LIMIT_STORE=memory}
 * (the default, one edge) or {@code redis} (two edges or more, {@code ABUSE_RATE_LIMIT_REDIS_URL}).
 * A Redis that fails is bypassed, never fatal: the edge limits per instance until it answers.
 */
@Configuration
class RateLimitStoreConfig {

  private static final Logger logger = LoggerFactory.getLogger(RateLimitStoreConfig.class);
  private static final int MAX_KEYS = 10_000;

  /** Where the state lives. */
  enum Store {
    MEMORY,
    REDIS;

    static Store parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(
            "ABUSE_RATE_LIMIT_STORE must be memory or redis, not '" + value + "'", e);
      }
    }
  }

  @Bean
  BucketStore bucketStore(
      @Value("${orazaka.edge.rate-limit.store:${ABUSE_RATE_LIMIT_STORE:memory}}") String store,
      @Value("${orazaka.edge.rate-limit.redis-url}") String redisUrl,
      Clock clock) {
    InMemoryBucketStore local = new InMemoryBucketStore(clock, MAX_KEYS);
    if (Store.parse(store) == Store.MEMORY) {
      return local;
    }
    logger.info("rate limits are kept in Redis, shared by every edge instance");
    return new ResilientBucketStore(new RedisBucketStore(RedisClient.create(redisUrl)), local);
  }

  @Bean
  DailyCounterStore dailyCounterStore(
      @Value("${orazaka.edge.rate-limit.store:${ABUSE_RATE_LIMIT_STORE:memory}}") String store,
      @Value("${orazaka.edge.rate-limit.redis-url}") String redisUrl) {
    InMemoryDailyCounterStore local = new InMemoryDailyCounterStore();
    if (Store.parse(store) == Store.MEMORY) {
      return local;
    }
    return new ResilientDailyCounterStore(
        new RedisDailyCounterStore(RedisClient.create(redisUrl)), local);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
