-- Same users and grants as db/roles.sql, with throwaway test passwords.
-- Runs once as the container's superuser before the app starts.
CREATE ROLE transport_migrator LOGIN PASSWORD 'migrator';
GRANT CONNECT, CREATE ON DATABASE transport TO transport_migrator;
CREATE SCHEMA transport AUTHORIZATION transport_migrator;

CREATE ROLE transport_mcp LOGIN PASSWORD 'mcp';
GRANT CONNECT ON DATABASE transport TO transport_mcp;
GRANT USAGE ON SCHEMA transport TO transport_mcp;
ALTER DEFAULT PRIVILEGES FOR ROLE transport_migrator IN SCHEMA transport
  GRANT SELECT ON TABLES TO transport_mcp;
ALTER ROLE transport_mcp SET default_transaction_read_only = on;
ALTER ROLE transport_mcp SET statement_timeout = '5s';
