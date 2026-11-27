package io.github.mrmtd.transport.invocation;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

/**
 * Who is calling a tool. {@link #key()} is what the rate limiter counts against: the MCP session for
 * MCP clients, the client IP for the REST API.
 */
public record Caller(String transport, String key, @Nullable String sessionId,
                     @Nullable String clientName, @Nullable String clientVersion) {

    public static final String MCP = "mcp";
    public static final String REST = "rest";

    public static Caller mcp(@Nullable McpSyncServerExchange exchange) {
        if (exchange == null || exchange.sessionId() == null) {
            return new Caller(MCP, "mcp:unknown", null, null, null);
        }
        var info = exchange.getClientInfo();
        return new Caller(MCP, "mcp:" + exchange.sessionId(), exchange.sessionId(),
                info == null ? null : info.name(), info == null ? null : info.version());
    }

    public static Caller rest(HttpServletRequest request) {
        return new Caller(REST, "ip:" + request.getRemoteAddr(), null, null, null);
    }
}
