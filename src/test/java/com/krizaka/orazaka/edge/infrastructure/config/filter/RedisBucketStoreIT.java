package com.krizaka.orazaka.edge.infrastructure.config.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.orazaka.test.AbstractContainerIntegrationTest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The shared limiter store (ADR-083 slice 3) against a real Redis: two edge instances — two
 * clients, no memory in common — spend the same bucket, so a key may spend its tier once, not once
 * per instance.
 */
@SpringBootTest(properties = "management.server.port=0")
class RedisBucketStoreIT extends AbstractContainerIntegrationTest {

  @Value("${spring.data.redis.host}")
  private String host;

  @Value("${spring.data.redis.port}")
  private int port;

  private RedisClient first;
  private RedisClient second;
  private RedisBucketStore edgeA;
  private RedisBucketStore edgeB;

  @BeforeEach
  void twoEdges() {
    String uri = "redis://" + host + ":" + port;
    first = RedisClient.create(uri);
    second = RedisClient.create(uri);
    edgeA = new RedisBucketStore(first.connect());
    edgeB = new RedisBucketStore(second.connect());
  }

  @AfterEach
  void close() {
    first.shutdown();
    second.shutdown();
  }

  private static String key() {
    return "k-" + UUID.randomUUID();
  }

  @Test
  @DisplayName("two instances share one bucket: five calls a minute in all, not five each")
  void bucketIsSharedAcrossInstances() {
    String key = key();
    int allowed = 0;
    for (int i = 0; i < 4; i++) {
      allowed += edgeA.take(key, 5).allowed() ? 1 : 0;
      allowed += edgeB.take(key, 5).allowed() ? 1 : 0;
    }
    assertThat(allowed).as("5 tokens, 8 attempts, two instances").isEqualTo(5);
    ApiKeyRateLimiter.Decision refused = edgeB.take(key, 5);
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.overLimit()).isTrue();
    assertThat(refused.remaining()).isZero();
    assertThat(refused.limit()).isEqualTo(5);
    assertThat(refused.retryAfterSeconds()).isBetween(1L, 12L);
  }

  @Test
  @DisplayName("keys are independent, and the verdict says how many calls are left")
  void keysAreIndependent() {
    String a = key();
    String b = key();
    assertThat(edgeA.take(a, 3).remaining()).isEqualTo(2);
    assertThat(edgeB.take(a, 3).remaining()).isEqualTo(1);
    assertThat(edgeA.take(b, 3).remaining()).as("another key starts full").isEqualTo(2);
  }

  @Test
  @DisplayName("tokens come back with time, at the tier's rate, on the server's clock")
  void refillsOnTheServersClock() throws InterruptedException {
    String key = key();
    for (int i = 0; i < 60; i++) {
      edgeA.take(key, 60);
    }
    assertThat(edgeB.take(key, 60).allowed()).isFalse();
    Thread.sleep(1_200); // 60/min refills one token a second
    assertThat(edgeB.take(key, 60).allowed()).isTrue();
  }

  @Test
  @DisplayName("a raised tier applies at once: the limit is the caller's, not the store's")
  void limitFollowsTheCaller() {
    String key = key();
    for (int i = 0; i < 2; i++) {
      edgeA.take(key, 2);
    }
    assertThat(edgeA.take(key, 2).allowed()).isFalse();
    // The operator raised the tier: the same bucket refills at the new rate (one token a 600 ms).
    try {
      Thread.sleep(700);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(edgeB.take(key, 100).allowed()).isTrue();
  }

  @Test
  @DisplayName("a bucket the clients stop using expires by itself: the store does not fill up")
  void idleBucketsExpire() throws Exception {
    String key = key();
    edgeA.take(key, 10);
    try (StatefulRedisConnection<String, String> raw = first.connect()) {
      Long ttl = raw.sync().pttl(RedisBucketStore.PREFIX + key);
      assertThat(ttl).isPositive();
    }
  }

  @Test
  @DisplayName("the daily quota is shared too: two instances count one day's calls together")
  void dailyQuotaIsShared() {
    RedisDailyCounterStore a = new RedisDailyCounterStore(first.connect());
    RedisDailyCounterStore b = new RedisDailyCounterStore(second.connect());
    String key = key();
    LocalDate day = LocalDate.now(java.time.ZoneOffset.UTC);
    assertThat(a.increment(key, day)).isEqualTo(1);
    assertThat(b.increment(key, day)).isEqualTo(2);
    assertThat(a.increment(key, day)).isEqualTo(3);
    assertThat(a.increment(key, day.plusDays(1))).as("another day starts again").isEqualTo(1);
  }
}
