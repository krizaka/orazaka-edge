package com.krizaka.orazaka.edge.infrastructure.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A limiter must not become the outage: when the shared store fails, the edge keeps limiting with
 * its own memory — per instance, the way it did before the store existed — and goes back to the
 * shared one when it answers again.
 */
class ResilientBucketStoreTest {

  private static final class Flaky implements BucketStore {
    final AtomicBoolean down = new AtomicBoolean();
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public ApiKeyRateLimiter.Decision take(String digest, int limit) {
      calls.incrementAndGet();
      if (down.get()) {
        throw new IllegalStateException("redis is down");
      }
      return new ApiKeyRateLimiter.Decision(true, false, limit, 41, 0);
    }

    @Override
    public int trackedKeys() {
      return 0;
    }
  }

  private final Flaky shared = new Flaky();
  private final ResilientBucketStore store =
      new ResilientBucketStore(shared, new InMemoryBucketStore(java.time.Clock.systemUTC(), 100));

  @Test
  @DisplayName("while the shared store answers, its verdict is the verdict")
  void usesTheSharedStore() {
    assertThat(store.take("k", 60).remaining()).isEqualTo(41);
    assertThat(store.degraded()).isFalse();
  }

  @Test
  @DisplayName("when it fails the call is still limited, by the instance's own bucket")
  void fallsBackToMemory() {
    shared.down.set(true);
    int allowed = 0;
    for (int i = 0; i < 8; i++) {
      allowed += store.take("k", 5).allowed() ? 1 : 0;
    }
    assertThat(allowed).as("5 tokens in the local bucket").isEqualTo(5);
    assertThat(store.degraded()).isTrue();
  }

  @Test
  @DisplayName("it tries the shared store again after a pause and recovers")
  void recovers() {
    shared.down.set(true);
    store.take("k", 5);
    int callsWhileDown = shared.calls.get();
    store.take("k", 5);
    assertThat(shared.calls.get()).as("not hammered while down").isEqualTo(callsWhileDown);

    shared.down.set(false);
    store.retryNow();
    assertThat(store.take("k", 60).remaining()).isEqualTo(41);
    assertThat(store.degraded()).isFalse();
  }
}
