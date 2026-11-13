package com.example.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-06T10:00:00Z"));
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    void allowsUpToTheLimitPerWindow() {
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void reportsSecondsUntilTheWindowResets() {
        clock.advance(Duration.ofSeconds(45).plusMillis(300));
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        FixedWindowRateLimiter.Decision decision = limiter.tryAcquire("a");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(15);

        clock.advance(Duration.ofMillis(14_699));
        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void resetsInTheNextWindowAndSweepsOldKeys() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("a");
        }
        limiter.tryAcquire("b");
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void keepsKeysIndependent() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter(0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
