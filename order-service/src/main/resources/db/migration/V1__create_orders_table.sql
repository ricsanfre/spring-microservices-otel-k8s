CREATE TABLE IF NOT EXISTS orders
(
    id           UUID          NOT NULL PRIMARY KEY,
    user_id      UUID          NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    total_amount DOUBLE PRECISION    NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders (user_id);
