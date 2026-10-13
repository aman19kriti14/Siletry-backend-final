package com.siletry.lead;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LeadRateLimiterTest {

    @Test
    void allowsUpToMaxThenRecoversAfterWindow() {
        AtomicLong now = new AtomicLong(1_000_000);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
            @Override public long millis() { return now.get(); }
        };
        LeadRateLimiter limiter = new LeadRateLimiter(2, 60_000, clock);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();

        now.addAndGet(60_000);
        assertThat(limiter.tryAcquire("a")).isTrue();

        now.addAndGet(120_000);
        limiter.evictExpired();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire(null)).isTrue();
    }
}
