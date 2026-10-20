package com.krizaka.orazaka.edge.infrastructure.config.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimitStoreConfigTest {

  @Test
  @DisplayName("the store is memory by default, and the choice is parsed strictly")
  void storeChoice() {
    assertThat(RateLimitStoreConfig.Store.parse("memory"))
        .isEqualTo(RateLimitStoreConfig.Store.MEMORY);
    assertThat(RateLimitStoreConfig.Store.parse(" Redis "))
        .isEqualTo(RateLimitStoreConfig.Store.REDIS);
    assertThatThrownBy(() -> RateLimitStoreConfig.Store.parse("etcd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("memory or redis");
  }

  @Test
  @DisplayName("without Redis the stores are the in-memory ones")
  void memoryStores() {
    RateLimitStoreConfig config = new RateLimitStoreConfig();
    assertThat(config.bucketStore("memory", "redis://localhost:1", Clock.systemUTC()))
        .isInstanceOf(InMemoryBucketStore.class);
    assertThat(config.dailyCounterStore("memory", "redis://localhost:1"))
        .isInstanceOf(InMemoryDailyCounterStore.class);
  }
}
