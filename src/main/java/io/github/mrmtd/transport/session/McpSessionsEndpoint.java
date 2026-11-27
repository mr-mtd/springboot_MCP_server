package io.github.mrmtd.transport.session;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code GET /actuator/mcpsessions} lists active MCP sessions; {@code /actuator/mcpsessions/{id}} shows one. */
@Component
@Endpoint(id = "mcpsessions")
public class McpSessionsEndpoint {

    private final McpSessionRegistry sessions;

    public McpSessionsEndpoint(McpSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @ReadOperation
    public List<McpSessionState.Snapshot> sessions() {
        return sessions.list();
    }

    @ReadOperation
    public McpSessionState.Snapshot session(@Selector String sessionId) {
        return sessions.find(sessionId).orElse(null);
    }
}
