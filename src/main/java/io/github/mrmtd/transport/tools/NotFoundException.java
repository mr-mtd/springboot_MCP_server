package io.github.mrmtd.transport.tools;

/**
 * An id that doesn't match any row. It extends {@link IllegalArgumentException} so the MCP server
 * still reports it as a tool error, while the REST API can map it to 404 instead of 400.
 */
public class NotFoundException extends IllegalArgumentException {

    public NotFoundException(String message) {
        super(message);
    }
}
