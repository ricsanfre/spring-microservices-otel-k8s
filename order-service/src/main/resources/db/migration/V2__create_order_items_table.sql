CREATE TABLE IF NOT EXISTS order_items
(
    id         UUID          NOT NULL PRIMARY KEY,
    order_id   UUID          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id VARCHAR(255)  NOT NULL,
    quantity   INTEGER       NOT NULL,
    unit_price DOUBLE PRECISION NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items (order_id);
