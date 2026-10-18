package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Requests-per-minute limit per service key (ADR-083): a token bucket per {@code oz_} key whose
 * size is the requests-per-minute of the key owner's rate-limit tier. The tier is a row the
 * operator edits in the identity database ({@code rate_limits}); the identity service sends it with
 * every key exchange, so a change applies on the next call and the edge keeps no copy of it.
 *
 * <p>A bucket holds up to one minute's worth of calls (a client may burst) and refills
 * continuously. A refused call is told how long to wait ({@code Retry-After}); it never reaches a
 * backend and never spends the key's daily quota.
 *
 * <p>Three modes, so the limit can be rolled out like the billing enforcement was: {@code observe}
 * (the default) counts and flags but never refuses, {@code enforce} answers {@code 429}, {@code
 * off} keeps nothing. Env {@code ABUSE_RATE_LIMIT_MODE}; {@code ABUSE_RATE_LIMIT_DEFAULT_RPM} is
 * the limit used when the identity service sends no tier.
 *
 * <p>State is in memory, per edge instance: with N instances a key may spend N times its tier. A
 * shared store (Redis, as the conversation service's bucket4j filter uses) is the move when the
 * edge scales out (ADR-083, slice 3). Keys are held as SHA-256 digests, never in clear. A full
 * table forgets idle buckets (those already refilled to capacity — forgetting them changes nothing)
 * and never flushes a busy one, so a flood cannot reset another key's debt.
 */
@Component
class ApiKeyRateLimiter {

  /** How the limit is applied. */
  enum Mode {
    OFF,
    OBSERVE,
    ENFORCE;

    static Mode parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(
            "ABUSE_RATE_LIMIT_MODE must be off, observe or enforce, not '" + value + "'", e);
      }
    }
  }

  /**
   * The verdict on one call.
   *
   * @param allowed whether the call may proceed (always {@code true} outside enforce mode)
   * @param overLimit whether the bucket was empty: the call would be refused in enforce mode
   * @param limit the requests per minute applied
   * @param remaining whole calls left in the bucket
   * @param retryAfterSeconds when {@code overLimit}, seconds until a call can succeed (at least 1)
   */
  record Decision(
      boolean allowed, boolean overLimit, int limit, int remaining, long retryAfterSeconds) {}

  private static final int DEFAULT_MAX_KEYS = 10_000;
  private static final double EPSILON = 1e-9;

  private final Mode mode;
  private final int defaultLimit;
  private final Clock clock;
  private final int maxKeys;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  @Autowired
  ApiKeyRateLimiter(
      @Value("${orazaka.edge.rate-limit.mode:${ABUSE_RATE_LIMIT_MODE:observe}}") String mode,
      @Value(
              "${orazaka.edge.rate-limit.default-requests-per-minute:${ABUSE_RATE_LIMIT_DEFAULT_RPM:600}}")
          int defaultLimit) {
    this(Mode.parse(mode), defaultLimit, Clock.systemUTC());
  }

  ApiKeyRateLimiter(Mode mode, int defaultLimit, Clock clock) {
    this(mode, defaultLimit, clock, DEFAULT_MAX_KEYS);
  }

  ApiKeyRateLimiter(Mode mode, int defaultLimit, Clock clock, int maxKeys) {
    if (defaultLimit < 1) {
      throw new IllegalArgumentException("the default requests per minute must be at least 1");
    }
    this.mode = mode;
    this.defaultLimit = defaultLimit;
    this.clock = clock;
    this.maxKeys = maxKeys;
  }

  /** A limiter that never limits, for the tests that are about something else. */
  static ApiKeyRateLimiter off() {
    return new ApiKeyRateLimiter(Mode.OFF, 1, Clock.systemUTC());
  }

  /** Whether a bucket is kept at all. */
  boolean active() {
    return mode != Mode.OFF;
  }

  /**
   * Counts one call of {@code apiKey}.
   *
   * @param apiKey the key, as presented
   * @param requestsPerMinute the key owner's tier limit; {@code null} or not positive uses the edge
   *     default
   * @return the verdict
   */
  Decision tryAcquire(String apiKey, Integer requestsPerMinute) {
    int limit =
        requestsPerMinute == null || requestsPerMinute < 1 ? defaultLimit : requestsPerMinute;
    if (mode == Mode.OFF) {
      return new Decision(true, false, limit, limit, 0);
    }
    String digest = KeyDigest.of(apiKey);
    Bucket bucket = buckets.get(digest);
    if (bucket == null) {
      if (buckets.size() >= maxKeys) {
        sweepIdle();
      }
      bucket = buckets.computeIfAbsent(digest, k -> new Bucket(limit, clock.millis()));
    }
    Decision taken = bucket.take(limit, clock.millis());
    return mode == Mode.ENFORCE
        ? taken
        : new Decision(
            true, taken.overLimit(), taken.limit(), taken.remaining(), taken.retryAfterSeconds());
  }

  /** Number of keys currently tracked. */
  int trackedKeys() {
    return buckets.size();
  }

  private void sweepIdle() {
    long now = clock.millis();
    buckets.entrySet().removeIf(e -> e.getValue().isFull(now));
  }

  /** A token bucket sized by the limit of the moment: refilled at {@code limit / 60} a second. */
  private static final class Bucket {
    private double tokens;
    private long lastMillis;
    private int limit;

    Bucket(int limit, long nowMillis) {
      this.limit = limit;
      this.tokens = limit;
      this.lastMillis = nowMillis;
    }

    synchronized Decision take(int newLimit, long nowMillis) {
      refill(newLimit, nowMillis);
      if (tokens >= 1.0 - EPSILON) {
        tokens = Math.max(0.0, tokens - 1.0);
        return new Decision(true, false, limit, (int) Math.floor(tokens + EPSILON), 0);
      }
      double millisUntilToken = (1.0 - tokens) * 60_000.0 / limit;
      long seconds = Math.max(1L, (long) Math.ceil(millisUntilToken / 1000.0 - EPSILON));
      return new Decision(false, true, limit, 0, seconds);
    }

    synchronized boolean isFull(long nowMillis) {
      refill(limit, nowMillis);
      return tokens >= limit - EPSILON;
    }

    private void refill(int newLimit, long nowMillis) {
      long elapsed = Math.max(0L, nowMillis - lastMillis);
      tokens = Math.min(newLimit, tokens + elapsed * (double) newLimit / 60_000.0);
      limit = newLimit;
      lastMillis = nowMillis;
    }
  }
}
