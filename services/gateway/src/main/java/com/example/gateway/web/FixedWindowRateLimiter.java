package com.example.gateway.web;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fixed-window counter per key. A single gateway instance serves the demo, so no
 * shared store is needed. Windows from earlier periods are swept when a new period starts.
 */
public final class FixedWindowRateLimiter {

    /** Allowed, or rejected with the whole seconds until the window resets (at least 1). */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(long start, int count) {
    }

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong();

    public FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    public Decision tryAcquire(String key) {
        long now = clock.millis();
        long start = now - Math.floorMod(now, windowMillis);
        sweep(start);
        Window window = windows.compute(key, (k, old) ->
                old == null || old.start() != start ? new Window(start, 1) : new Window(start, old.count() + 1));
        if (window.count() <= limit) {
            return new Decision(true, 0);
        }
        long remaining = start + windowMillis - now;
        return new Decision(false, Math.max(1, (remaining + 999) / 1000));
    }

    int trackedKeys() {
        return windows.size();
    }

    private void sweep(long currentStart) {
        long previous = lastSweep.get();
        if (previous != currentStart && lastSweep.compareAndSet(previous, currentStart)) {
            windows.values().removeIf(w -> w.start() < currentStart);
        }
    }
}
