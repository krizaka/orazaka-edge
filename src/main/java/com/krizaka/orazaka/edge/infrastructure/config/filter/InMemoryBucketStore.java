package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buckets in this instance's memory. With several edges a key may spend its tier on each of them;
 * {@link RedisBucketStore} is the shared answer. A full table forgets idle buckets (already
 * refilled to capacity: forgetting them changes nothing) and never flushes a busy one.
 */
final class InMemoryBucketStore implements BucketStore {

  private static final double EPSILON = 1e-9;

  private final Clock clock;
  private final int maxKeys;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  InMemoryBucketStore(Clock clock, int maxKeys) {
    this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
    this.maxKeys = maxKeys;
  }

  @Override
  public ApiKeyRateLimiter.Decision take(String digest, int limit) {
    Bucket bucket = buckets.get(digest);
    if (bucket == null) {
      if (buckets.size() >= maxKeys) {
        sweepIdle();
      }
      bucket = buckets.computeIfAbsent(digest, k -> new Bucket(limit, clock.millis()));
    }
    return bucket.take(limit, clock.millis());
  }

  @Override
  public int trackedKeys() {
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

    synchronized ApiKeyRateLimiter.Decision take(int newLimit, long nowMillis) {
      refill(newLimit, nowMillis);
      if (tokens >= 1.0 - EPSILON) {
        tokens = Math.max(0.0, tokens - 1.0);
        return new ApiKeyRateLimiter.Decision(
            true, false, limit, (int) Math.floor(tokens + EPSILON), 0);
      }
      double millisUntilToken = (1.0 - tokens) * 60_000.0 / limit;
      long seconds = Math.max(1L, (long) Math.ceil(millisUntilToken / 1000.0 - EPSILON));
      return new ApiKeyRateLimiter.Decision(false, true, limit, 0, seconds);
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
