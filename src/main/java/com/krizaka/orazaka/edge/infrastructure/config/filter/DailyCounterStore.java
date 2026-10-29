package com.krizaka.orazaka.edge.infrastructure.config.filter;

import java.time.LocalDate;

/**
 * Where the daily call counts of the keys live (ADR-083 slice 3): memory, or Redis shared by every
 * edge.
 */
interface DailyCounterStore {

  /**
   * Counts one call.
   *
   * @param digest the SHA-256 digest of the key
   * @param day the UTC day
   * @return how many calls the key has made that day, this one included
   */
  long increment(String digest, LocalDate day);
}
