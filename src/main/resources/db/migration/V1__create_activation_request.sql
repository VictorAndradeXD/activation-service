CREATE TABLE activation_request (
    id              UUID         PRIMARY KEY,
    external_id     VARCHAR(64)  NOT NULL,
    customer_id     VARCHAR(64)  NOT NULL,
    product_code    VARCHAR(64)  NOT NULL,
    client_id       VARCHAR(128) NOT NULL,
    correlation_id  VARCHAR(64),
    status          VARCHAR(20)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL,
    -- The real guarantee against duplicates: concurrent inserts with the same external_id cannot both commit.
    CONSTRAINT uk_activation_request_external_id UNIQUE (external_id),
    CONSTRAINT ck_activation_request_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Listing is always scoped to the calling client and ordered by created_at DESC.
CREATE INDEX ix_activation_request_client_created
    ON activation_request (client_id, created_at DESC);
CREATE INDEX ix_activation_request_client_customer_created
    ON activation_request (client_id, customer_id, created_at DESC);
CREATE INDEX ix_activation_request_client_status_created
    ON activation_request (client_id, status, created_at DESC);

-- Worker polling: partial index only holds unfinished requests, so it stays small as COMPLETED rows accumulate.
CREATE INDEX ix_activation_request_due
    ON activation_request (next_attempt_at)
    WHERE status IN ('PENDING', 'PROCESSING');
