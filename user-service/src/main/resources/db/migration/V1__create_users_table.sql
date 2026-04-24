CREATE TABLE IF NOT EXISTS users
(
    id          UUID         NOT NULL PRIMARY KEY,
    idp_subject VARCHAR(255) NOT NULL,
    email       VARCHAR(255) NOT NULL,
    username    VARCHAR(50)  NOT NULL,
    first_name  VARCHAR(100),
    last_name   VARCHAR(100),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_users_idp_subject UNIQUE (idp_subject),
    CONSTRAINT uq_users_email       UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_users_idp_subject ON users (idp_subject);
