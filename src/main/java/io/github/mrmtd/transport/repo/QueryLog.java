package io.github.mrmtd.transport.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Times a repository query and logs it: DEBUG for every query (name, rows, duration), WARN when a
 * query is slow. The DB user has a 5 s statement timeout, so a slow warning is an early signal
 * before queries start failing.
 */
final class QueryLog {

    private static final Logger log = LoggerFactory.getLogger("io.github.mrmtd.transport.repo.queries");

    static final long SLOW_QUERY_MS = 1_000;

    private QueryLog() {
    }

    static <T> T timed(String query, Supplier<T> body, ToIntFunction<T> rowCount) {
        long start = System.nanoTime();
        try {
            T result = body.get();
            long ms = (System.nanoTime() - start) / 1_000_000;
            int rows = rowCount.applyAsInt(result);
            if (ms >= SLOW_QUERY_MS) {
                log.warn("Slow query query={} rows={} durationMs={} (statement timeout is 5s)", query, rows, ms);
            } else {
                log.debug("query={} rows={} durationMs={}", query, rows, ms);
            }
            return result;
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.error("Query failed query={} durationMs={} error={}", query, ms, e.toString());
            throw e;
        }
    }
}
