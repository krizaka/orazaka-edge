package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A limiter must not become the outage. While the shared store answers, its verdict is the verdict;
 * when it fails, this instance limits with its own memory (per instance, as it did before the
 * shared store existed) and tries the shared store again after a pause, not on every call.
 */
final class ResilientBucketStore implements BucketStore {

  private static final Logger logger = LoggerFactory.getLogger(ResilientBucketStore.class);
  private static final long RETRY_AFTER_MILLIS = 5_000L;

  private final BucketStore shared;
  private final BucketStore local;
  private final AtomicLong retryAt = new AtomicLong();

  ResilientBucketStore(BucketStore shared, BucketStore local) {
    this.shared = Objects.requireNonNull(shared, "shared store cannot be null");
    this.local = Objects.requireNonNull(local, "local store cannot be null");
  }

  @Override
  public ApiKeyRateLimiter.Decision take(String digest, int limit) {
    if (System.currentTimeMillis() >= retryAt.get()) {
      try {
        ApiKeyRateLimiter.Decision decision = shared.take(digest, limit);
        if (retryAt.get() != 0) {
          logger.info("the shared rate-limit store answers again");
          retryAt.set(0);
        }
        return decision;
      } catch (RuntimeException unavailable) {
        if (retryAt.getAndSet(System.currentTimeMillis() + RETRY_AFTER_MILLIS) == 0) {
          logger.warn(
              "the shared rate-limit store failed ({}): limiting per instance until it answers",
              unavailable.getClass().getSimpleName());
        }
      }
    }
    return local.take(digest, limit);
  }

  /** Whether the shared store is being bypassed right now. */
  boolean degraded() {
    return retryAt.get() != 0;
  }

  /** Tries the shared store again on the next call (a test, or an operator's nudge). */
  void retryNow() {
    retryAt.set(1);
  }

  @Override
  public int trackedKeys() {
    return local.trackedKeys();
  }
}
