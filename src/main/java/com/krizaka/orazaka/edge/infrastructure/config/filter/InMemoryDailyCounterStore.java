package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Daily counts in this instance's memory; a restart gives the day back. Yesterday's counts are
 * dropped.
 */
final class InMemoryDailyCounterStore implements DailyCounterStore {

  private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
  private volatile LocalDate day;

  @Override
  public long increment(String digest, LocalDate today) {
    synchronized (this) {
      if (!today.equals(day)) {
        counts.clear();
        day = today;
      }
    }
    return counts.computeIfAbsent(digest, k -> new AtomicInteger()).incrementAndGet();
  }
}
