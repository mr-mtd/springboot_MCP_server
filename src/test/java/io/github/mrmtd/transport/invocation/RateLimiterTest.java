package io.github.mrmtd.transport.invocation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    final AtomicLong now = new AtomicLong();
    final RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 3, 1.0), now::get);

    @Test
    void allowsABurstUpToCapacityThenRefillsAtTheSteadyRate() {
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isEqualTo(1);   // empty: retry in 1 s

        now.addAndGet(1_000_000_000L);                        // 1 s later, one token is back
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isPositive();
    }

    @Test
    void eachCallerHasItsOwnBucket() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        assertThat(limiter.tryAcquire("a")).isPositive();
        assertThat(limiter.tryAcquire("b")).isZero();
    }

    @Test
    void neverRefillsPastCapacityAndEvictsIdleCallers() {
        limiter.tryAcquire("a");
        now.addAndGet(3_600_000_000_000L);                    // an hour idle
        limiter.evictIdle();
        assertThat(limiter.trackedCallers()).isZero();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a")).isZero();
        }
        assertThat(limiter.tryAcquire("a")).isPositive();
    }

    @Test
    void canBeTurnedOff() {
        var off = new RateLimiter(new RateLimitProperties(false, 1, 0.001), now::get);
        for (int i = 0; i < 10; i++) {
            assertThat(off.tryAcquire("a")).isZero();
        }
    }
}
