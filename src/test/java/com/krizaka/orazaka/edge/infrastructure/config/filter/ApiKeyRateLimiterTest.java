package com.krizaka.orazaka.edge.infrastructure.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The per-key rate limit of the edge (ADR-083): a token bucket per service key, sized by the
 * requests-per-minute of the key owner's tier — a row the operator edits, not a constant.
 */
class ApiKeyRateLimiterTest {

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-18T12:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final MutableClock clock = new MutableClock();
  private final ApiKeyRateLimiter limiter =
      new ApiKeyRateLimiter(ApiKeyRateLimiter.Mode.ENFORCE, 600, clock);

  @Test
  @DisplayName("a key may spend a minute's worth at once, then waits for the refill")
  void burstThenRefusal() {
    for (int i = 0; i < 60; i++) {
      assertThat(limiter.tryAcquire("oz_a", 60).allowed()).as("call %d", i).isTrue();
    }

    ApiKeyRateLimiter.Decision refused = limiter.tryAcquire("oz_a", 60);

    assertThat(refused.allowed()).isFalse();
    assertThat(refused.limit()).isEqualTo(60);
    assertThat(refused.remaining()).isZero();
    // 60 per minute refills one token per second.
    assertThat(refused.retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  @DisplayName("tokens come back with time, at the tier's rate")
  void refills() {
    for (int i = 0; i < 60; i++) {
      limiter.tryAcquire("oz_a", 60);
    }
    assertThat(limiter.tryAcquire("oz_a", 60).allowed()).isFalse();

    clock.advance(Duration.ofSeconds(3));

    assertThat(limiter.tryAcquire("oz_a", 60).allowed()).isTrue();
    assertThat(limiter.tryAcquire("oz_a", 60).allowed()).isTrue();
    assertThat(limiter.tryAcquire("oz_a", 60).allowed()).isTrue();
    assertThat(limiter.tryAcquire("oz_a", 60).allowed()).isFalse();
  }

  @Test
  @DisplayName("Retry-After is the whole seconds until the next token, never zero")
  void retryAfterRoundsUp() {
    for (int i = 0; i < 6; i++) {
      limiter.tryAcquire("oz_slow", 6);
    }

    // 6 per minute = one token every 10 seconds.
    assertThat(limiter.tryAcquire("oz_slow", 6).retryAfterSeconds()).isEqualTo(10);
    clock.advance(Duration.ofMillis(9_500));
    assertThat(limiter.tryAcquire("oz_slow", 6).retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  @DisplayName("keys do not share a bucket")
  void keysAreIndependent() {
    for (int i = 0; i < 5; i++) {
      limiter.tryAcquire("oz_a", 5);
    }

    assertThat(limiter.tryAcquire("oz_a", 5).allowed()).isFalse();
    assertThat(limiter.tryAcquire("oz_b", 5).allowed()).isTrue();
  }

  @Test
  @DisplayName("a tier changed by the operator applies on the next call, up or down")
  void limitFollowsTheTier() {
    for (int i = 0; i < 5; i++) {
      limiter.tryAcquire("oz_a", 5);
    }
    assertThat(limiter.tryAcquire("oz_a", 5).allowed()).isFalse();

    // Raised to 600: the bucket is refilled at the new rate, and the new capacity is available.
    clock.advance(Duration.ofSeconds(30));

    assertThat(limiter.tryAcquire("oz_a", 600).allowed()).isTrue();
    assertThat(limiter.tryAcquire("oz_a", 600).limit()).isEqualTo(600);
  }

  @Test
  @DisplayName("a tier the identity service did not send uses the edge default")
  void defaultWhenNoTier() {
    ApiKeyRateLimiter.Decision d = limiter.tryAcquire("oz_a", null);

    assertThat(d.limit()).isEqualTo(600);
    assertThat(d.allowed()).isTrue();
  }

  @Test
  @DisplayName("observe mode counts and flags but never refuses")
  void observeNeverRefuses() {
    ApiKeyRateLimiter observing = new ApiKeyRateLimiter(ApiKeyRateLimiter.Mode.OBSERVE, 600, clock);
    for (int i = 0; i < 5; i++) {
      assertThat(observing.tryAcquire("oz_a", 5).allowed()).isTrue();
    }

    ApiKeyRateLimiter.Decision over = observing.tryAcquire("oz_a", 5);

    assertThat(over.allowed()).isTrue();
    assertThat(over.overLimit()).isTrue();
  }

  @Test
  @DisplayName("off mode does not even keep a bucket")
  void offIsFree() {
    ApiKeyRateLimiter off = new ApiKeyRateLimiter(ApiKeyRateLimiter.Mode.OFF, 600, clock);

    for (int i = 0; i < 10_000; i++) {
      assertThat(off.tryAcquire("oz_a", 1).allowed()).isTrue();
    }
    assertThat(off.trackedKeys()).isZero();
  }

  @Test
  @DisplayName("idle full buckets are forgotten, busy ones are never flushed")
  void evictsOnlyIdleBuckets() {
    ApiKeyRateLimiter small = new ApiKeyRateLimiter(ApiKeyRateLimiter.Mode.ENFORCE, 600, clock, 4);
    for (int i = 0; i < 3; i++) {
      small.tryAcquire("oz_idle" + i, 60);
    }
    for (int i = 0; i < 60; i++) {
      small.tryAcquire("oz_busy", 60);
    }
    assertThat(small.tryAcquire("oz_busy", 60).allowed()).isFalse();
    clock.advance(Duration.ofSeconds(1));

    // The table is full: a new key triggers the sweep of full (idle) buckets only.
    small.tryAcquire("oz_new", 60);

    assertThat(small.trackedKeys()).isLessThanOrEqualTo(4);
    // The busy key kept its debt: it was not given a fresh bucket by a flush.
    assertThat(small.tryAcquire("oz_busy", 60).remaining()).isZero();
  }
}
