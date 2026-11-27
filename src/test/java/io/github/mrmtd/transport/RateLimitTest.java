package io.github.mrmtd.transport;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A tiny bucket (3 calls, almost no refill) so the limit is easy to hit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "transport.rate-limit.capacity=3",
        "transport.rate-limit.refill-per-second=0.01"})
class RateLimitTest extends PostgresTestBase {

    @LocalServerPort
    int port;

    @Test
    void restCallsOverTheLimitGet429WithRetryAfter() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/loads/420")).build();

        for (int i = 0; i < 3; i++) {
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
        HttpResponse<String> limited = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void mcpCallsOverTheLimitComeBackAsToolErrorsPerSession() {
        try (var client = newClient(); var other = newClient()) {
            client.initialize();
            other.initialize();
            var call = new McpSchema.CallToolRequest("getLoadStatus", Map.of("loadNumber", "420"));

            for (int i = 0; i < 3; i++) {
                assertThat(client.callTool(call).isError()).isNotEqualTo(Boolean.TRUE);
            }
            McpSchema.CallToolResult limited = client.callTool(call);
            assertThat(limited.isError()).isTrue();
            assertThat(limited.content()).first()
                    .isInstanceOfSatisfying(McpSchema.TextContent.class,
                            text -> assertThat(text.text()).contains("Rate limit exceeded"));

            // The limit is per session: another client is unaffected.
            assertThat(other.callTool(call).isError()).isNotEqualTo(Boolean.TRUE);
        }
    }

    private io.modelcontextprotocol.client.McpSyncClient newClient() {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build())
                .clientInfo(new McpSchema.Implementation("rate-test", "1.0")).build();
    }
}
