CREATE TABLE IF NOT EXISTS users
(
    id                        UUID         NOT NULL PRIMARY KEY,
    idp_subject               VARCHAR(255) NOT NULL,
    email                     VARCHAR(255) NOT NULL,
    username                  VARCHAR(50)  NOT NULL,
    first_name                VARCHAR(100),
    last_name                 VARCHAR(100),
    -- Shipping address
    address_street            VARCHAR(255),
    address_city              VARCHAR(100),
    address_state             VARCHAR(100),
    address_postal_code       VARCHAR(20),
    address_country           VARCHAR(2),
    -- Billing account (card display metadata only — no PAN, no CVV)
    billing_card_holder       VARCHAR(255),
    billing_card_last4        VARCHAR(4),
    billing_card_expiry       VARCHAR(5),
    billing_same_as_shipping  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_users_idp_subject UNIQUE (idp_subject),
    CONSTRAINT uq_users_email       UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_users_idp_subject ON users (idp_subject);
