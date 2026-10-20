package com.krizaka.orazaka.edge.infrastructure.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryBucketStoreTest {

  @Test
  @DisplayName(
      "the in-memory store is a token bucket per key, and a full table forgets only idle buckets")
  void bucketsAndTheTable() {
    InMemoryBucketStore store = new InMemoryBucketStore(Clock.systemUTC(), 2);
    assertThat(store.take("a", 2).allowed()).isTrue();
    assertThat(store.take("a", 2).allowed()).isTrue();
    assertThat(store.take("a", 2).allowed()).isFalse();
    assertThat(store.take("b", 2).allowed()).isTrue();
    // The table holds two keys: a third finds no room and sweeps the idle (full) ones — never "a",
    // which is empty.
    assertThat(store.take("c", 2).allowed()).isTrue();
    assertThat(store.take("a", 2).allowed())
        .as("a flood cannot reset another key's debt")
        .isFalse();
  }
}
