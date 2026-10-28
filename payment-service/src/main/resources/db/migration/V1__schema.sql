-- Event store do agregado Pagamento.
-- A PK (payment_id, versao) é a concorrência otimista: dois comandos simultâneos
-- tentando gravar a mesma versão => um deles falha e tenta de novo (Caso 7).
CREATE TABLE payment_events (
    payment_id UUID         NOT NULL,
    version       INT          NOT NULL,
    type         VARCHAR(100) NOT NULL,
    data        JSONB        NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (payment_id, version)
);

-- Estado da saga orquestradora (Checkout Saga).
CREATE TABLE checkout_saga (
    order_id         VARCHAR(64) PRIMARY KEY,
    state            VARCHAR(40) NOT NULL,
    capture_deadline_at TIMESTAMPTZ,
    version            INT         NOT NULL,
    data             JSONB       NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX checkout_saga_deadline ON checkout_saga (state, capture_deadline_at);

-- Linha do tempo de cada saga: "em que passo está o pedido 48213?"
CREATE TABLE checkout_saga_history (
    id          BIGSERIAL PRIMARY KEY,
    order_id   VARCHAR(64) NOT NULL,
    description   TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX checkout_saga_history_order ON checkout_saga_history (order_id);

-- Transactional Outbox (Caso 5).
CREATE TABLE outbox (
    seq          BIGSERIAL PRIMARY KEY,
    topic       VARCHAR(100) NOT NULL,
    key        VARCHAR(64)  NOT NULL,
    message     JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_pending ON outbox (seq) WHERE published_at IS NULL;

-- Inbox: mensagens já processadas por consumidor (Caso 4).
CREATE TABLE processed_messages (
    consumer    VARCHAR(100) NOT NULL,
    message_id   UUID         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, message_id)
);

-- O que caiu na DLQ e precisa de conciliação (Caso 6).
CREATE TABLE pending_reconciliation (
    id              BIGSERIAL PRIMARY KEY,
    topic          VARCHAR(100) NOT NULL,
    key           VARCHAR(64),
    type            VARCHAR(100) NOT NULL,
    message        TEXT         NOT NULL,
    error            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reprocessed_at TIMESTAMPTZ
);
