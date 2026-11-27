package io.github.mrmtd.transport.session;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable per-session state; read it through {@link #snapshot()}. */
public final class McpSessionState {

    private final String sessionId;
    private final @Nullable String clientName;
    private final @Nullable String clientVersion;
    private final Instant startedAt;
    private volatile Instant lastSeenAt;
    private final Map<String, AtomicLong> toolCalls = new ConcurrentHashMap<>();

    McpSessionState(String sessionId, @Nullable String clientName, @Nullable String clientVersion, Instant now) {
        this.sessionId = sessionId;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
        this.startedAt = now;
        this.lastSeenAt = now;
    }

    void touch(Instant now) {
        lastSeenAt = now;
    }

    void recordToolCall(String tool, Instant now) {
        toolCalls.computeIfAbsent(tool, t -> new AtomicLong()).incrementAndGet();
        lastSeenAt = now;
    }

    String sessionId() {
        return sessionId;
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant lastSeenAt() {
        return lastSeenAt;
    }

    long totalToolCalls() {
        return toolCalls.values().stream().mapToLong(AtomicLong::get).sum();
    }

    Snapshot snapshot() {
        Map<String, Long> calls = new TreeMap<>();
        toolCalls.forEach((tool, n) -> calls.put(tool, n.get()));
        return new Snapshot(sessionId, clientName, clientVersion, startedAt, lastSeenAt, totalToolCalls(), calls);
    }

    public record Snapshot(String sessionId, @Nullable String clientName, @Nullable String clientVersion,
                           Instant startedAt, Instant lastSeenAt, long totalToolCalls,
                           Map<String, Long> toolCalls) {
    }
}
