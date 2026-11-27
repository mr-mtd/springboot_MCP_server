package io.github.mrmtd.transport;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.github.mrmtd.transport.session.McpSessionRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real server on a random port and talks to it the way an MCP client does:
 * initialize, tools/list, tools/call over Streamable HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndToEndTest extends PostgresTestBase {

    @LocalServerPort
    int port;

    @Autowired
    McpSessionRegistry sessions;

    @Autowired
    MeterRegistry meters;

    McpSyncClient client;

    @BeforeEach
    void connect() {
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build())
                .clientInfo(new McpSchema.Implementation("test-client", "1.0"))
                .build();
        client.initialize();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    @Test
    void listsTheThreeReadOnlyTools() {
        var tools = client.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("getLoadStatus", "getCarrierDetails", "findDelayedShipments");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.outputSchema()).isNotNull();
            // The injected McpSyncServerExchange is not a tool argument.
            assertThat(((Map<?, ?>) tool.inputSchema().get("properties")).containsKey("exchange")).isFalse();
        });
    }

    @Test
    void tracksStateForEachMcpSession() {
        client.callTool(new McpSchema.CallToolRequest("getLoadStatus", Map.of("loadNumber", "420")));
        client.callTool(new McpSchema.CallToolRequest("getLoadStatus", Map.of("loadNumber", "421")));
        client.callTool(new McpSchema.CallToolRequest("getCarrierDetails", Map.of("carrierId", 7)));

        var mine = sessions.list().stream()
                .filter(s -> s.toolCalls().getOrDefault("getLoadStatus", 0L) >= 2
                        && s.toolCalls().containsKey("getCarrierDetails"))
                .toList();
        assertThat(mine).singleElement().satisfies(s -> {
            assertThat(s.clientName()).isEqualTo("test-client");
            assertThat(s.totalToolCalls()).isEqualTo(3);
        });

        // A second client gets its own session and its own state.
        try (var other = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build())
                .clientInfo(new McpSchema.Implementation("other-client", "1.0")).build()) {
            other.initialize();
            other.callTool(new McpSchema.CallToolRequest("getLoadStatus", Map.of("loadNumber", "420")));
            assertThat(sessions.list()).anySatisfy(s -> {
                assertThat(s.clientName()).isEqualTo("other-client");
                assertThat(s.totalToolCalls()).isEqualTo(1);
            });
        }
    }

    @Test
    void recordsDurationAndFailureMetricsPerTool() {
        client.callTool(new McpSchema.CallToolRequest("getLoadStatus", Map.of("loadNumber", "420")));
        client.callTool(new McpSchema.CallToolRequest("getLoadStatus", Map.of("loadNumber", "LD-999999")));

        var success = meters.get("transport.tool.invocations")
                .tags("tool", "getLoadStatus", "transport", "mcp", "outcome", "success").timer();
        assertThat(success.count()).isPositive();
        assertThat(success.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();

        assertThat(meters.get("transport.tool.failures")
                .tags("tool", "getLoadStatus", "transport", "mcp", "outcome", "not_found").counter().count())
                .isPositive();
    }

    @Test
    void callsGetLoadStatus() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("getLoadStatus", Map.of("loadNumber", "LD-000420")));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.structuredContent()).get("loadNumber")).isEqualTo("LD-000420");
    }

    @Test
    void unknownLoadComesBackAsToolError() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("getLoadStatus", Map.of("loadNumber", "LD-999999")));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).first()
                .isInstanceOfSatisfying(McpSchema.TextContent.class,
                        text -> assertThat(text.text()).contains("No load found"));
    }

    @Test
    void callsFindDelayedShipmentsWithFilters() {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("findDelayedShipments",
                Map.of("minHoursLate", 4, "includeDelivered", true, "limit", 5)));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        Map<?, ?> body = (Map<?, ?>) result.structuredContent();
        assertThat(body.get("count")).isEqualTo(5);
    }
}
