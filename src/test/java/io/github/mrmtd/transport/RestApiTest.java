package io.github.mrmtd.transport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The REST mirror under /api, its error mapping, and the OpenAPI document behind Swagger UI. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestApiTest extends PostgresTestBase {

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void loadStatus() throws Exception {
        var response = get("/api/loads/420");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"loadNumber\":\"LD-000420\"");
    }

    @Test
    void carrierById() throws Exception {
        var response = get("/api/carriers/7");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"id\":7");
    }

    @Test
    void delayedShipments() throws Exception {
        var response = get("/api/shipments/delayed?includeDelivered=true&limit=3");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"count\":3");
    }

    @Test
    void unknownLoadIs404() throws Exception {
        var response = get("/api/loads/LD-999999");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("No load found");
    }

    @Test
    void badInputIs400() throws Exception {
        var response = get("/api/shipments/delayed?originState=Texas");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("two-letter");
    }

    @Test
    void openApiDocumentListsOnlyTheRestMirror() throws Exception {
        var response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/api/loads/{loadNumber}", "/api/shipments/delayed")
                .doesNotContain("\"/mcp\"");
    }

    @Test
    void metricsAndSessionsAreExposedOnActuator() throws Exception {
        get("/api/loads/420");
        assertThat(get("/actuator/metrics/transport.tool.invocations").statusCode()).isEqualTo(200);
        var prometheus = get("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("transport_tool_invocations_seconds_count");
        assertThat(get("/actuator/mcpsessions").statusCode()).isEqualTo(200);
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui/index.html")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
