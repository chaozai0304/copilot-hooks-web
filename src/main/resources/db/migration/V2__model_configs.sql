CREATE TABLE model_configs (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(120) NOT NULL,
    provider           VARCHAR(80)  NOT NULL DEFAULT 'OpenAI Compatible',
    base_url           TEXT         NOT NULL,
    api_key_cipher     TEXT,
    chat_model         VARCHAR(160) NOT NULL,
    embedding_model    VARCHAR(160) NOT NULL,
    embedding_dimensions INTEGER    NOT NULL DEFAULT 1536,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    default_config     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_model_configs_enabled ON model_configs(enabled);
CREATE UNIQUE INDEX uk_model_configs_default ON model_configs(default_config) WHERE default_config;
