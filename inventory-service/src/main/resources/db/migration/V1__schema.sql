CREATE TABLE inventory (
    sku        VARCHAR(64) PRIMARY KEY,
    available INT NOT NULL CHECK (available >= 0)
);

CREATE TABLE reservation (
    order_id     VARCHAR(64) PRIMARY KEY,
    status        VARCHAR(20) NOT NULL,
    items         JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    seq          BIGSERIAL PRIMARY KEY,
    topic       VARCHAR(100) NOT NULL,
    key        VARCHAR(64)  NOT NULL,
    message     JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_pending ON outbox (seq) WHERE published_at IS NULL;

CREATE TABLE processed_messages (
    consumer    VARCHAR(100) NOT NULL,
    message_id   UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, message_id)
);

-- Catálogo de exemplo usado nos cenários.
INSERT INTO inventory (sku, available) VALUES
    ('SKU-TSHIRT', 1000),
    ('SKU-MUG', 500),
    ('SKU-SOLD-OUT', 0);
