package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Daily counts in this instance's memory; a restart gives the day back. Yesterday's counts are
 * dropped.
 */
final class InMemoryDailyCounterStore implements DailyCounterStore {

  /**
   * One UTC day's counters: swapped whole at midnight, so no lock is held (virtual threads must not
   * pin).
   */
  private record Day(LocalDate date, Map<String, AtomicInteger> counts) {}

  private final AtomicReference<Day> current = new AtomicReference<>();

  @Override
  public long increment(String digest, LocalDate today) {
    Day day = current.get();
    while (day == null || day.date().isBefore(today)) {
      Day fresh = new Day(today, new ConcurrentHashMap<>());
      if (current.compareAndSet(day, fresh)) {
        day = fresh;
      } else {
        day = current.get();
      }
    }
    return day.counts().computeIfAbsent(digest, k -> new AtomicInteger()).incrementAndGet();
  }
}
