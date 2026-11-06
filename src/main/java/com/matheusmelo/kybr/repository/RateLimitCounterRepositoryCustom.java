package com.matheusmelo.kybr.repository;

import java.time.Instant;

/** Custom fragment: the one atomic operation the fixed-window limiter needs. */
public interface RateLimitCounterRepositoryCustom {

    /**
     * Atomically increments the counter {@code id}, creating it with {@code expiresAt} if absent,
     * and returns the new count. One {@code findAndModify}: {@code $inc count}, {@code $setOnInsert
     * expiresAt}, upsert, return new.
     */
    long incrementAndGet(String id, Instant expiresAt);
}
