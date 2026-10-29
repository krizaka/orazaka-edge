package com.krizaka.orazaka.edge.infrastructure.config.filter;

/**
 * Where the rate-limit buckets live (ADR-083 slice 3): one token bucket per key digest, sized by
 * the limit the caller passes — the key owner's tier — and refilled at that limit a minute. The
 * edge's limiter decides the mode and the default; the store only keeps the tokens.
 */
interface BucketStore {

  /**
   * Takes one token.
   *
   * @param digest the SHA-256 digest of the key, never the key
   * @param limit the requests per minute of the moment
   * @return the verdict: whether a token was taken, how many are left, when to retry
   */
  ApiKeyRateLimiter.Decision take(String digest, int limit);

  /**
   * How many keys this instance holds in memory (0 for a store that keeps them elsewhere).
   *
   * @return the count
   */
  int trackedKeys();
}
