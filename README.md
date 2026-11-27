# Transport MCP server

A Spring Boot [MCP](https://modelcontextprotocol.io) server that exposes read-only tools over a
synthetic freight database in PostgreSQL: carriers, loads, and load tracking events. It's a
learning project.

- Java 21, Spring Boot 4.2, Spring AI 2.1 (`spring-ai-starter-mcp-server-webmvc`)
- Transport: Streamable HTTP at `POST /mcp`
- Database: PostgreSQL (Amazon RDS in us-east-2), queried with `JdbcClient`, schema managed by Flyway

## Tools

| Tool | Inputs | Returns |
| --- | --- | --- |
| `getLoadStatus` | `loadNumber` (`LD-000420`, or just `420`) | Status, carrier, lane, scheduled vs actual times, hours late, latest event |
| `getCarrierDetails` | `carrierId` or `mcNumber` (exactly one) | Carrier profile, open loads, deliveries and on-time % over the last 90 days |
| `findDelayedShipments` | all optional: `minHoursLate` (2), `carrierId`, `originState`, `destinationState`, `includeDelivered` (false), `limit` (20, max 100) | Late loads, most late first, with the latest reported delay reason |

**Late** means more than 2 hours past `delivery_scheduled_at`, measured against `delivered_at`, or
against now while the load is still on the road. That rule lives in one place:
[`DelayRule`](src/main/java/io/github/mrmtd/transport/repo/DelayRule.java).

All tools are annotated `readOnlyHint = true` and return records, so clients get an `outputSchema`
and `structuredContent`. Bad input or an unknown id comes back as a tool result with
`isError: true` and a readable message, so the calling model can correct itself.

## Database

```
carrier 1 ──< load 1 ──< load_event
```

| File | Run as | Purpose |
| --- | --- | --- |
| [`db/roles.sql`](db/roles.sql) | RDS master (`postgres`), once | Creates `transport_migrator` (owns the schema) and `transport_mcp` (read-only, 5 s statement timeout) |
| [`V1__schema.sql`](src/main/resources/db/migration/V1__schema.sql) | `transport_migrator` | Tables, constraints, indexes |
| [`V2__seed.sql`](src/main/resources/db/migration/V2__seed.sql) | `transport_migrator` | 50 carriers, 2,000 loads, about 13k events. Same data every run; dates relative to `now()` |

The server connects as **`transport_mcp`**, so even a buggy tool can't change data.

## Running it

### Against RDS

Download the RDS certificate bundle once:

```bash
curl -o ~/global-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
```

Then start the server as the read-only user:

```bash
export DB_URL="jdbc:postgresql://transport-db.cps0062uqgpv.us-east-2.rds.amazonaws.com:5432/transport?sslmode=verify-full&sslrootcert=$HOME/global-bundle.pem"
export DB_USERNAME=transport_mcp
export DB_PASSWORD='...'        # single quotes, so the shell leaves special characters alone
mvn spring-boot:run
```

The server listens on `http://localhost:8080/mcp`. Health: `http://localhost:8080/actuator/health`.

### Migrations

The runtime user can't write, so Flyway only runs under the `migrate` profile, as `transport_migrator`:

```bash
export MIGRATOR_PASSWORD='...'
mvn spring-boot:run -Dspring-boot.run.profiles=migrate
```

On the RDS database, where V1 and V2 were run by hand, the first migrate run records a baseline at
version 2 and changes nothing. New migrations go in `src/main/resources/db/migration` as `V3__...sql`.

To refresh the synthetic data (dates move forward to today), run `V2__seed.sql` again as
`transport_migrator`. It clears the three tables first.

## Trying the tools

**MCP Inspector** (needs Node):

```bash
npx @modelcontextprotocol/inspector
```

Choose transport **Streamable HTTP**, URL `http://localhost:8080/mcp`, then **Connect** and open **Tools**.

**Raw JSON-RPC with curl**, to see what an MCP client actually sends:

```bash
H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')

# 1. initialize; the response header Mcp-Session-Id identifies the session
curl -si "${H[@]}" localhost:8080/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'

SID=...   # paste the Mcp-Session-Id value

# 2. tell the server we're ready
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:8080/mcp -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3. call a tool
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:8080/mcp \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"findDelayedShipments","arguments":{"limit":3}}}'
```

## Tests

```bash
mvn test
```

The tests need Docker. They start Postgres 18 with Testcontainers, set it up like RDS (same two
users, Flyway as `transport_migrator`, app as `transport_mcp`), and then:

- call each tool method directly against the seeded data
- start the server on a random port and call it with the MCP Java SDK client (`initialize`,
  `tools/list`, `tools/call`), checking `structuredContent`, output schemas and `isError` results
