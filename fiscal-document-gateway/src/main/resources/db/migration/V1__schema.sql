-- Numeração da série: um número consumido nunca é reaproveitado para outra venda.
CREATE SEQUENCE invoice_number_seq START 1;

-- Uma NF-e por pedido. A chave é gravada ANTES do envio à SEFAZ (Caso 3).
CREATE TABLE invoice (
    id            UUID PRIMARY KEY,
    order_id     VARCHAR(64) NOT NULL UNIQUE,
    number        BIGINT      NOT NULL,
    access_key  CHAR(44)    NOT NULL UNIQUE,
    status        VARCHAR(30) NOT NULL,
    version        INT         NOT NULL,
    data         JSONB       NOT NULL,
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
