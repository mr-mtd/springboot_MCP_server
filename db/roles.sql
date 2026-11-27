-- One-time setup, run as the RDS master user (postgres) on the transport database.
-- Replace the passwords; keep them in a password manager, never in this repo.

-- 1. Owner of the schema, used only by migrations (Flyway)
CREATE ROLE transport_migrator LOGIN PASSWORD 'CHANGE_ME_1';
GRANT CONNECT, CREATE ON DATABASE transport TO transport_migrator;
GRANT transport_migrator TO postgres;   -- the RDS master isn't a superuser, so it needs this
CREATE SCHEMA transport AUTHORIZATION transport_migrator;

-- 2. Read-only user for the MCP server
CREATE ROLE transport_mcp LOGIN PASSWORD 'CHANGE_ME_2';
GRANT CONNECT ON DATABASE transport TO transport_mcp;
GRANT USAGE ON SCHEMA transport TO transport_mcp;
ALTER DEFAULT PRIVILEGES FOR ROLE transport_migrator IN SCHEMA transport
  GRANT SELECT ON TABLES TO transport_mcp;
ALTER ROLE transport_mcp SET default_transaction_read_only = on;
ALTER ROLE transport_mcp SET statement_timeout = '5s';
