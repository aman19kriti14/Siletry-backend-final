package com.siletry.lead;

import com.siletry.ops.OpsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limit per client (keyed by hashed IP), in memory.
 * Fine for a single backend instance. If the backend is ever scaled to several
 * replicas, move this to Redis or a database table so the limit is shared.
 */
@Component
public class LeadRateLimiter {

    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final int max;
    private final long windowMs;
    private final Clock clock;

    @Autowired
    public LeadRateLimiter(OpsProperties props) {
        this(props.leads().rateLimitMax(), props.leads().rateLimitWindow().toMillis(), Clock.systemUTC());
    }

    LeadRateLimiter(int max, long windowMs, Clock clock) {
        this.max = max;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** Records the attempt and returns true if it is within the limit. */
    public boolean tryAcquire(String clientKey) {
        if (clientKey == null) clientKey = "unknown";
        if (hits.size() > MAX_TRACKED_CLIENTS) evictExpired();
        long now = clock.millis();
        Deque<Long> q = hits.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= windowMs) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }

    @Scheduled(fixedDelay = 300_000)
    void evictExpired() {
        long now = clock.millis();
        hits.entrySet().removeIf(e -> {
            Deque<Long> q = e.getValue();
            synchronized (q) {
                while (!q.isEmpty() && now - q.peekFirst() >= windowMs) q.pollFirst();
                return q.isEmpty();
            }
        });
    }
}
