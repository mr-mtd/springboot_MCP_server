package io.github.mrmtd.transport.session;

import io.github.mrmtd.transport.invocation.Caller;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side state for each MCP session. The Streamable HTTP transport gives every client an
 * {@code Mcp-Session-Id} at initialize, and the client sends it on every later request. This keeps,
 * per session: who the client is, when it started, when it was last seen, and how many tool calls it
 * made, by tool. A session is tracked from its first tool call.
 *
 * <p>Sessions end when the client sends {@code DELETE /mcp} ({@link McpSessionFilter}) or after
 * {@code transport.mcp.session-idle-timeout} with no requests. Active sessions are a gauge,
 * {@code transport.mcp.sessions.active}, and listed at {@code /actuator/mcpsessions}.
 */
@Component
public class McpSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpSessionRegistry.class);

    private final Map<String, McpSessionState> sessions = new ConcurrentHashMap<>();
    private final Duration idleTimeout;
    private final Clock clock;

    @Autowired
    public McpSessionRegistry(@Value("${transport.mcp.session-idle-timeout:30m}") Duration idleTimeout,
                              MeterRegistry meters) {
        this(idleTimeout, meters, Clock.systemUTC());
    }

    McpSessionRegistry(Duration idleTimeout, MeterRegistry meters, Clock clock) {
        this.idleTimeout = idleTimeout;
        this.clock = clock;
        Gauge.builder("transport.mcp.sessions.active", sessions, Map::size)
                .description("MCP sessions seen and not yet closed or expired")
                .register(meters);
    }

    /** Called on every request that carries a session id. */
    public void touch(String sessionId) {
        McpSessionState state = sessions.get(sessionId);
        if (state != null) {
            state.touch(clock.instant());
        }
    }

    /** Called for every tool call; creates the session's state on its first call. */
    public void recordToolCall(Caller caller, String tool) {
        Instant now = clock.instant();
        McpSessionState state = sessions.computeIfAbsent(caller.sessionId(), id -> {
            log.info("MCP session started mcpSession={} client={} clientVersion={}",
                    id, caller.clientName(), caller.clientVersion());
            return new McpSessionState(id, caller.clientName(), caller.clientVersion(), now);
        });
        state.recordToolCall(tool, now);
    }

    /** The client ended the session with DELETE /mcp. */
    public void close(String sessionId) {
        McpSessionState state = sessions.remove(sessionId);
        if (state != null) {
            log.info("MCP session closed by client mcpSession={} toolCalls={} lasted={}",
                    sessionId, state.totalToolCalls(), Duration.between(state.startedAt(), clock.instant()));
        }
    }

    @Scheduled(fixedDelay = 60_000)
    void expireIdle() {
        Instant cutoff = clock.instant().minus(idleTimeout);
        sessions.values().removeIf(state -> {
            if (state.lastSeenAt().isBefore(cutoff)) {
                log.info("MCP session expired after {} idle mcpSession={} toolCalls={}",
                        idleTimeout, state.sessionId(), state.totalToolCalls());
                return true;
            }
            return false;
        });
    }

    public Optional<McpSessionState.Snapshot> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId)).map(McpSessionState::snapshot);
    }

    public List<McpSessionState.Snapshot> list() {
        return sessions.values().stream()
                .map(McpSessionState::snapshot)
                .sorted(Comparator.comparing(McpSessionState.Snapshot::startedAt))
                .toList();
    }
}
