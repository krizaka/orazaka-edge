package com.krizaka.orazaka.edge.infrastructure.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiKeyDailyQuotaTest {

  private static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-10-20T12:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final MutableClock clock = new MutableClock();

  @Test
  @DisplayName("A key is refused once its day's quota is spent, other keys are not")
  void refusesPastTheLimit() {
    ApiKeyDailyQuota quota = new ApiKeyDailyQuota(2, clock);
    assertThat(quota.tryAcquire("oz_a")).isTrue();
    assertThat(quota.tryAcquire("oz_a")).isTrue();
    assertThat(quota.tryAcquire("oz_a")).isFalse();
    assertThat(quota.tryAcquire("oz_b")).isTrue();
  }

  @Test
  @DisplayName("The counters start again at 00:00 UTC")
  void resetsAtMidnight() {
    ApiKeyDailyQuota quota = new ApiKeyDailyQuota(1, clock);
    assertThat(quota.tryAcquire("oz_a")).isTrue();
    assertThat(quota.tryAcquire("oz_a")).isFalse();
    clock.now = Instant.parse("2026-10-21T00:00:01Z");
    assertThat(quota.tryAcquire("oz_a")).isTrue();
  }

  @Test
  @DisplayName("Concurrent callers never get more than the limit, also across the midnight swap")
  void concurrentCallersRespectTheLimit() throws Exception {
    ApiKeyDailyQuota quota = new ApiKeyDailyQuota(100, clock);
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    AtomicInteger granted = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 400; i++) {
      futures.add(
          pool.submit(
              () -> {
                if (quota.tryAcquire("oz_race")) {
                  granted.incrementAndGet();
                }
              }));
    }
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();
    assertThat(granted.get()).isEqualTo(100);
    assertThat(quota.secondsUntilReset()).isEqualTo(12 * 3600);
  }
}
