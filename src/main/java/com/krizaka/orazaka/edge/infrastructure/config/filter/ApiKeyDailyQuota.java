package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Daily request quota per service token ({@code oz_} key): a key that is leaked or looping spends
 * at most {@code limit} calls a UTC day, then answers {@code 429} until 00:00 UTC. Keys are held as
 * SHA-256 digests, never in clear; counters live in memory (a restart gives the day back — the
 * global spend budget of the engine is the backstop).
 *
 * <p>{@code orazaka.edge.api-key-daily-limit} (env {@code AUTH_API_KEY_DAILY_REQUESTS}, default
 * 2000); a negative value switches the quota off.
 */
@Component
class ApiKeyDailyQuota {

  private final int limit;
  private final Clock clock;

  /**
   * One UTC day's counters: swapped whole at midnight, so no lock is held (virtual threads must not
   * pin).
   */
  private record Day(LocalDate date, Map<String, AtomicInteger> counts) {}

  private final AtomicReference<Day> current = new AtomicReference<>();

  @Autowired
  ApiKeyDailyQuota(
      @Value("${orazaka.edge.api-key-daily-limit:${AUTH_API_KEY_DAILY_REQUESTS:2000}}") int limit) {
    this(limit, Clock.systemUTC());
  }

  ApiKeyDailyQuota(int limit, Clock clock) {
    this.limit = limit;
    this.clock = clock;
  }

  static ApiKeyDailyQuota unlimited() {
    return new ApiKeyDailyQuota(-1, Clock.systemUTC());
  }

  /** Counts one call of {@code apiKey}; {@code false} when its day's quota is already spent. */
  boolean tryAcquire(String apiKey) {
    if (limit < 0) {
      return true;
    }
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    Day day = current.get();
    while (day == null || day.date().isBefore(today)) {
      Day fresh = new Day(today, new ConcurrentHashMap<>());
      if (current.compareAndSet(day, fresh)) {
        day = fresh;
      } else {
        day = current.get();
      }
    }
    return day.counts()
            .computeIfAbsent(KeyDigest.of(apiKey), k -> new AtomicInteger())
            .incrementAndGet()
        <= limit;
  }

  /**
   * Seconds until 00:00 UTC, when the day's counts start again: the {@code Retry-After} of a 429.
   */
  long secondsUntilReset() {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    java.time.Instant midnight = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    return Math.max(1L, Duration.between(clock.instant(), midnight).toSeconds());
  }
}
