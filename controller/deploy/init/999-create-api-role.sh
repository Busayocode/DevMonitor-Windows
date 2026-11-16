#!/bin/bash
set -Eeuo pipefail

: "${CONTROLLER_API_DB_USER:?CONTROLLER_API_DB_USER is required}"
: "${CONTROLLER_API_DB_PASSWORD:?CONTROLLER_API_DB_PASSWORD is required}"

# Passwords are generated as hexadecimal strings so they are safe in a PostgreSQL URL.
if [[ ! "$CONTROLLER_API_DB_PASSWORD" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "CONTROLLER_API_DB_PASSWORD must be exactly 64 hexadecimal characters." >&2
  exit 1
fi

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=api_user="$CONTROLLER_API_DB_USER" \
  --set=api_password="$CONTROLLER_API_DB_PASSWORD" \
  --set=database_name="$POSTGRES_DB" <<'SQL'
CREATE ROLE :"api_user" LOGIN PASSWORD :'api_password';
GRANT CONNECT ON DATABASE :"database_name" TO :"api_user";
GRANT USAGE ON SCHEMA public TO :"api_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"api_user";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"api_user";
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO :"api_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"api_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO :"api_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO :"api_user";
SQL
