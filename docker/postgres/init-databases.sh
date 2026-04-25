#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# PostgreSQL init script — creates one database + dedicated user per service.
#
# This script is mounted into /docker-entrypoint-initdb.d/ inside the postgres
# container and runs exactly once when the data volume is first created.
#
# Add a new create_service_db call here as each microservice is implemented.
# ──────────────────────────────────────────────────────────────────────────────
set -e

create_service_db() {
  local user="$1" password="$2" db="$3"
  echo "  → creating user '$user' and database '$db'"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" \
    -c "CREATE USER $user WITH PASSWORD '$password';"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" \
    -c "CREATE DATABASE $db OWNER $user;"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" \
    -c "GRANT ALL ON SCHEMA public TO $user;"
}

echo "==> Initialising e-commerce databases..."

# user: username=users   password=users   db=users   (user-service, port default 5432)
create_service_db users  users  users

# order: username=orders  password=orders  db=orders  (order-service — planned)
create_service_db orders orders orders

echo "==> Done."
