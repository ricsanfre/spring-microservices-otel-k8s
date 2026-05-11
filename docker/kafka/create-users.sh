#!/bin/bash
# ──────────────────────────────────────────────────────────────────────────────
# Kafka SASL/SCRAM bootstrap — runs in a separate kafka-init container.
#
# Connects to the INTERNAL (PLAINTEXT) listener on kafka:9094 where the
# ANONYMOUS principal is a super-user.  Creates SCRAM-SHA-512 credentials for
# each microservice and sets up ACLs so every service can only access the topics
# it needs.
#
# Passwords are read from the environment so they can be injected at runtime
# (e.g. from a .env file or CI secrets):
#   ORDER_SERVICE_KAFKA_PASSWORD       (default: order-service-secret)
#   NOTIFICATION_SERVICE_KAFKA_PASSWORD (default: notification-service-secret)
#   CART_SERVICE_KAFKA_PASSWORD         (default: cart-service-secret)
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

BOOTSTRAP="kafka:9094"          # INTERNAL (PLAINTEXT) listener — no auth needed
KAFKA_BIN="/opt/kafka/bin"

ORDER_PW="${ORDER_SERVICE_KAFKA_PASSWORD:-order-service-secret}"
NOTIFICATION_PW="${NOTIFICATION_SERVICE_KAFKA_PASSWORD:-notification-service-secret}"
CART_PW="${CART_SERVICE_KAFKA_PASSWORD:-cart-service-secret}"

echo "[kafka-init] Waiting for Kafka to be ready at ${BOOTSTRAP}..."
until "${KAFKA_BIN}/kafka-broker-api-versions.sh" \
      --bootstrap-server "${BOOTSTRAP}" >/dev/null 2>&1; do
  sleep 2
done
echo "[kafka-init] Kafka is ready."

# ── Topics ───────────────────────────────────────────────────────────────────
# Pre-create all topics so that consumers can subscribe without needing Create
# permission (auto.create.topics.enable is disabled on the broker).
echo "[kafka-init] Creating topics..."

create_topic() {
  local name="$1" partitions="${2:-1}" retention_ms="${3:-604800000}"
  "${KAFKA_BIN}/kafka-topics.sh" \
    --bootstrap-server "${BOOTSTRAP}" \
    --create \
    --if-not-exists \
    --topic "${name}" \
    --partitions "${partitions}" \
    --replication-factor 1 \
    --config "retention.ms=${retention_ms}"
  echo "[kafka-init]   topic '${name}' created (or already exists)."
}

create_topic "order.created.v1"
create_topic "order.confirmed.v1"

echo "[kafka-init] Topics created."

# ── SCRAM-SHA-512 users ──────────────────────────────────────────────────────
echo "[kafka-init] Creating SCRAM-SHA-512 credentials..."

create_user() {
  local name="$1" password="$2"
  "${KAFKA_BIN}/kafka-configs.sh" \
    --bootstrap-server "${BOOTSTRAP}" \
    --alter \
    --add-config "SCRAM-SHA-512=[iterations=8192,password=${password}]" \
    --entity-type users \
    --entity-name "${name}"
  echo "[kafka-init]   user '${name}' created."
}

create_user "order-service"        "${ORDER_PW}"
create_user "notification-service" "${NOTIFICATION_PW}"
create_user "cart-service"         "${CART_PW}"

# ── ACLs ──────────────────────────────────────────────────────────────────────
# Principle of least privilege:
#   order-service       → Write + Create + Describe on both order topics
#   notification-service → Read + Describe on order.confirmed.v1
#                          Read on consumer group notification-group
#   cart-service        → Read + Describe on order.confirmed.v1
#                          Read on consumer group cart-service-group
echo "[kafka-init] Creating ACLs..."

acl_topic() {
  local principal="$1" topic="$2"; shift 2
  "${KAFKA_BIN}/kafka-acls.sh" \
    --bootstrap-server "${BOOTSTRAP}" \
    --add \
    --allow-principal "User:${principal}" \
    --topic "${topic}" \
    "$@"
}

acl_group() {
  local principal="$1" group="$2"
  "${KAFKA_BIN}/kafka-acls.sh" \
    --bootstrap-server "${BOOTSTRAP}" \
    --add \
    --allow-principal "User:${principal}" \
    --group "${group}" \
    --operation Read
}

# order-service — producer on both topics
acl_topic "order-service" "order.created.v1"   --operation Write --operation Create --operation Describe
acl_topic "order-service" "order.confirmed.v1" --operation Write --operation Create --operation Describe

# notification-service — consumer on order.confirmed.v1
acl_topic "notification-service" "order.confirmed.v1" --operation Read --operation Describe
acl_group "notification-service" "notification-group"

# cart-service — consumer on order.confirmed.v1
acl_topic "cart-service" "order.confirmed.v1" --operation Read --operation Describe
acl_group "cart-service" "cart-service-group"

echo "[kafka-init] ACLs created successfully."
echo "[kafka-init] Done."
