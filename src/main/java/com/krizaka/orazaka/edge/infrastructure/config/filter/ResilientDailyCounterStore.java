package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The daily counts must not become the outage either: when the shared store fails the instance
 * counts in its own memory, and tries the shared one again after a pause.
 */
final class ResilientDailyCounterStore implements DailyCounterStore {

  private static final Logger logger = LoggerFactory.getLogger(ResilientDailyCounterStore.class);
  private static final long RETRY_AFTER_MILLIS = 5_000L;

  private final DailyCounterStore shared;
  private final DailyCounterStore local;
  private final AtomicLong retryAt = new AtomicLong();

  ResilientDailyCounterStore(DailyCounterStore shared, DailyCounterStore local) {
    this.shared = Objects.requireNonNull(shared, "shared store cannot be null");
    this.local = Objects.requireNonNull(local, "local store cannot be null");
  }

  @Override
  public long increment(String digest, LocalDate day) {
    if (System.currentTimeMillis() >= retryAt.get()) {
      try {
        long count = shared.increment(digest, day);
        retryAt.set(0);
        return count;
      } catch (RuntimeException unavailable) {
        if (retryAt.getAndSet(System.currentTimeMillis() + RETRY_AFTER_MILLIS) == 0) {
          logger.warn(
              "the shared daily-quota store failed ({}): counting per instance until it answers",
              unavailable.getClass().getSimpleName());
        }
      }
    }
    return local.increment(digest, day);
  }
}
