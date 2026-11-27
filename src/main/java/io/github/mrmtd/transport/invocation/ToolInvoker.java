package io.github.mrmtd.transport.invocation;

import io.github.mrmtd.transport.service.NotFoundException;
import io.github.mrmtd.transport.session.McpSessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The one path every tool call takes, from MCP or REST. In order, it:
 *
 * <ol>
 *   <li>puts the tool, transport and MCP session into the logging MDC, so every log line from the
 *       call (including the repository's query logs) carries them</li>
 *   <li>records the call against the MCP session</li>
 *   <li>applies the per-caller rate limit</li>
 *   <li>runs the tool and times it</li>
 *   <li>records metrics and writes one INFO line with the outcome and duration</li>
 * </ol>
 *
 * <p>Metrics (see /actuator/metrics and /actuator/prometheus):
 * <ul>
 *   <li>{@code transport.tool.invocations}: timer, tags {@code tool}, {@code transport}, {@code outcome}.
 *       Gives call count and duration (count, sum, max, and percentiles in Prometheus).</li>
 *   <li>{@code transport.tool.failures}: counter, same tags, for every outcome other than success.</li>
 * </ul>
 * Outcomes: {@code success}, {@code invalid_input}, {@code not_found}, {@code rate_limited}, {@code error}.
 */
@Component
public class ToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(ToolInvoker.class);

    static final String INVOCATIONS = "transport.tool.invocations";
    static final String FAILURES = "transport.tool.failures";

    private final RateLimiter rateLimiter;
    private final McpSessionRegistry sessions;
    private final MeterRegistry meters;

    public ToolInvoker(RateLimiter rateLimiter, McpSessionRegistry sessions, MeterRegistry meters) {
        this.rateLimiter = rateLimiter;
        this.sessions = sessions;
        this.meters = meters;
    }

    public <T> T invoke(String tool, Caller caller, Supplier<T> body) {
        try (var ignoredTool = MDC.putCloseable("tool", tool);
             var ignoredTransport = MDC.putCloseable("transport", caller.transport());
             var ignoredSession = MDC.putCloseable("mcpSession", caller.sessionId())) {

            if (caller.sessionId() != null) {
                sessions.recordToolCall(caller, tool);
            }

            long retryAfter = rateLimiter.tryAcquire(caller.key());
            if (retryAfter > 0) {
                record(tool, caller, "rate_limited", 0);
                log.warn("tool={} outcome=rate_limited caller={} retryAfterSeconds={}", tool, caller.key(), retryAfter);
                throw new RateLimitExceededException(retryAfter);
            }

            long start = System.nanoTime();
            try {
                T result = body.get();
                long nanos = System.nanoTime() - start;
                record(tool, caller, "success", nanos);
                log.info("tool={} outcome=success durationMs={}", tool, nanos / 1_000_000);
                return result;
            } catch (NotFoundException e) {
                long nanos = System.nanoTime() - start;
                record(tool, caller, "not_found", nanos);
                log.info("tool={} outcome=not_found durationMs={} message=\"{}\"", tool, nanos / 1_000_000, e.getMessage());
                throw e;
            } catch (IllegalArgumentException e) {
                long nanos = System.nanoTime() - start;
                record(tool, caller, "invalid_input", nanos);
                log.info("tool={} outcome=invalid_input durationMs={} message=\"{}\"", tool, nanos / 1_000_000, e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                long nanos = System.nanoTime() - start;
                record(tool, caller, "error", nanos);
                log.error("tool={} outcome=error durationMs={}", tool, nanos / 1_000_000, e);
                throw e;
            }
        }
    }

    private void record(String tool, Caller caller, String outcome, long nanos) {
        Timer.builder(INVOCATIONS)
                .description("Tool calls, timed, by tool, transport and outcome")
                .tags("tool", tool, "transport", caller.transport(), "outcome", outcome)
                .publishPercentileHistogram()
                .register(meters)
                .record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        if (!"success".equals(outcome)) {
            Counter.builder(FAILURES)
                    .description("Tool calls that did not succeed, by tool, transport and outcome")
                    .tags("tool", tool, "transport", caller.transport(), "outcome", outcome)
                    .register(meters)
                    .increment();
        }
    }
}
