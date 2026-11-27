package io.github.mrmtd.transport.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sees every request to the MCP endpoint. It puts the {@code Mcp-Session-Id} into the logging MDC,
 * marks the session as seen, and ends the session in the registry when the client sends DELETE.
 */
@Component
public class McpSessionFilter extends OncePerRequestFilter {

    static final String SESSION_HEADER = "Mcp-Session-Id";

    private final McpSessionRegistry sessions;

    public McpSessionFilter(McpSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sessionId = request.getHeader(SESSION_HEADER);
        if (sessionId == null) {
            chain.doFilter(request, response);
            return;
        }
        try (var ignored = MDC.putCloseable("mcpSession", sessionId)) {
            sessions.touch(sessionId);
            chain.doFilter(request, response);
            if ("DELETE".equals(request.getMethod()) && response.getStatus() < 400) {
                sessions.close(sessionId);
            }
        }
    }
}
