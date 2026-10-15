package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
  private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
  private volatile LocalDate day;

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
    synchronized (this) {
      if (!today.equals(day)) {
        counts.clear();
        day = today;
      }
    }
    return counts.computeIfAbsent(digest(apiKey), k -> new AtomicInteger()).incrementAndGet()
        <= limit;
  }

  private static String digest(String apiKey) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
