package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
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
 * <p>Where the buckets live is a {@link BucketStore} (ADR-083 slice 3): in memory, per edge
 * instance (the default — with N instances a key may spend N times its tier), or in Redis, shared
 * by every instance ({@code ABUSE_RATE_LIMIT_STORE=redis}), so a key spends its tier once. Keys are
 * held as SHA-256 digests, never in clear. The in-memory table forgets idle buckets (those already
 * refilled to capacity — forgetting them changes nothing) and never flushes a busy one, so a flood
 * cannot reset another key's debt.
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

  private final Mode mode;
  private final int defaultLimit;
  private final BucketStore store;

  @Autowired
  ApiKeyRateLimiter(
      @Value("${orazaka.edge.rate-limit.mode:${ABUSE_RATE_LIMIT_MODE:observe}}") String mode,
      @Value(
              "${orazaka.edge.rate-limit.default-requests-per-minute:${ABUSE_RATE_LIMIT_DEFAULT_RPM:600}}")
          int defaultLimit,
      BucketStore store) {
    this(Mode.parse(mode), defaultLimit, store);
  }

  ApiKeyRateLimiter(Mode mode, int defaultLimit, Clock clock) {
    this(mode, defaultLimit, new InMemoryBucketStore(clock, DEFAULT_MAX_KEYS));
  }

  ApiKeyRateLimiter(Mode mode, int defaultLimit, Clock clock, int maxKeys) {
    this(mode, defaultLimit, new InMemoryBucketStore(clock, maxKeys));
  }

  ApiKeyRateLimiter(Mode mode, int defaultLimit, BucketStore store) {
    if (defaultLimit < 1) {
      throw new IllegalArgumentException("the default requests per minute must be at least 1");
    }
    this.mode = mode;
    this.defaultLimit = defaultLimit;
    this.store = Objects.requireNonNull(store, "BucketStore cannot be null");
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
    Decision taken = store.take(KeyDigest.of(apiKey), limit);
    return mode == Mode.ENFORCE
        ? taken
        : new Decision(
            true, taken.overLimit(), taken.limit(), taken.remaining(), taken.retryAfterSeconds());
  }

  /** Number of keys currently tracked by this instance. */
  int trackedKeys() {
    return store.trackedKeys();
  }
}
