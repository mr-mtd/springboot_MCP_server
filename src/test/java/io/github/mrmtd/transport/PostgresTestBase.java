package io.github.mrmtd.transport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts one Postgres 18 container for all tests, set up like RDS: Flyway migrates as
 * transport_migrator (schema + seed), and the app itself connects as read-only transport_mcp.
 */
public abstract class PostgresTestBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("transport")
            .withInitScript("db/test-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "transport_mcp");
        registry.add("spring.datasource.password", () -> "mcp");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "transport");
        registry.add("spring.flyway.create-schemas", () -> "false");
        registry.add("spring.flyway.user", () -> "transport_migrator");
        registry.add("spring.flyway.password", () -> "migrator");
    }
}
