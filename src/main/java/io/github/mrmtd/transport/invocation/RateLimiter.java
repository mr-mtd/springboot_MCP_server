package io.github.mrmtd.transport.invocation;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A token bucket per caller. Each call takes one token; tokens refill at a steady rate up to the
 * bucket's capacity. So a caller can burst up to {@code capacity} calls, then is held to
 * {@code refillPerSecond}. This protects the small RDS instance (and its 5-connection pool) from one
 * chatty agent looping on a tool.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final long IDLE_EVICT_NANOS = Duration.ofMinutes(10).toNanos();

    private final RateLimitProperties props;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(RateLimitProperties props) {
        this(props, System::nanoTime);
    }

    RateLimiter(RateLimitProperties props, LongSupplier nanoClock) {
        this.props = props;
        this.nanoClock = nanoClock;
    }

    /**
     * Takes one token for {@code key}.
     *
     * @return 0 if the call may go ahead, otherwise how many seconds until a token is available
     */
    public long tryAcquire(String key) {
        if (!props.enabled()) {
            return 0;
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(props.capacity(), nanoClock.getAsLong()))
                .tryTake(props.capacity(), props.refillPerSecond(), nanoClock.getAsLong());
    }

    /** Drops buckets of callers that have been quiet for 10 minutes; a quiet caller's bucket is full anyway. */
    @Scheduled(fixedDelay = 60_000)
    void evictIdle() {
        long now = nanoClock.getAsLong();
        int before = buckets.size();
        buckets.values().removeIf(b -> b.idleNanos(now) > IDLE_EVICT_NANOS);
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("Evicted {} idle rate-limit buckets, {} remain", removed, buckets.size());
        }
    }

    int trackedCallers() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
        }

        synchronized long tryTake(int capacity, double refillPerSecond, long now) {
            double elapsedSeconds = (now - lastRefill) / 1e9;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) / refillPerSecond));
        }

        synchronized long idleNanos(long now) {
            return now - lastRefill;
        }
    }
}
