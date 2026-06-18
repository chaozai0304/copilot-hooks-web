-- Copilot Hooks Web - initial schema
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(80)  NOT NULL UNIQUE,
    display_name VARCHAR(160),
    email       VARCHAR(200),
    password_hash VARCHAR(200) NOT NULL,
    role        VARCHAR(32)  NOT NULL DEFAULT 'USER',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE api_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(120) NOT NULL,
    token_hash  VARCHAR(128) NOT NULL UNIQUE,
    token_prefix VARCHAR(16) NOT NULL,
    expires_at  TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_tokens_user ON api_tokens(user_id);

CREATE TABLE hook_sessions (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id         VARCHAR(128) NOT NULL,
    cwd                TEXT,
    source             VARCHAR(32),
    initial_prompt     TEXT,
    started_at         TIMESTAMPTZ,
    ended_at           TIMESTAMPTZ,
    end_reason         VARCHAR(32),
    last_event_at      TIMESTAMPTZ,
    event_count        INTEGER     NOT NULL DEFAULT 0,
    tool_count         INTEGER     NOT NULL DEFAULT 0,
    error_count        INTEGER     NOT NULL DEFAULT 0,
    prompt_count       INTEGER     NOT NULL DEFAULT 0,
    model              VARCHAR(120),
    total_tokens       BIGINT      NOT NULL DEFAULT 0,
    input_tokens       BIGINT      NOT NULL DEFAULT 0,
    output_tokens      BIGINT      NOT NULL DEFAULT 0,
    duration_ms        BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_hook_sessions_user_session UNIQUE (user_id, session_id)
);
CREATE INDEX idx_hook_sessions_user_started ON hook_sessions(user_id, started_at DESC);

CREATE TABLE hook_events (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_db_id   BIGINT      NOT NULL REFERENCES hook_sessions(id) ON DELETE CASCADE,
    session_id      VARCHAR(128) NOT NULL,
    event_type      VARCHAR(48) NOT NULL,
    event_time      TIMESTAMPTZ NOT NULL,
    seq             BIGSERIAL,
    cwd             TEXT,
    tool_name       VARCHAR(120),
    tool_args       JSONB,
    tool_result     JSONB,
    prompt          TEXT,
    error_message   TEXT,
    duration_ms     BIGINT,
    input_tokens    BIGINT,
    output_tokens   BIGINT,
    total_tokens    BIGINT,
    model           VARCHAR(120),
    raw_payload     JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_hook_events_session ON hook_events(session_db_id, event_time);
CREATE INDEX idx_hook_events_user_time ON hook_events(user_id, event_time DESC);
CREATE INDEX idx_hook_events_type ON hook_events(event_type);
CREATE INDEX idx_hook_events_tool ON hook_events(tool_name);

CREATE TABLE session_summaries (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_db_id   BIGINT      NOT NULL UNIQUE REFERENCES hook_sessions(id) ON DELETE CASCADE,
    session_id      VARCHAR(128) NOT NULL,
    title           VARCHAR(240),
    summary         TEXT,
    highlights      TEXT,
    tags            TEXT[],
    model           VARCHAR(120),
    token_usage     INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_session_summaries_user ON session_summaries(user_id);
CREATE INDEX idx_session_summaries_tags ON session_summaries USING GIN(tags);

-- Vector store table managed manually (Spring AI PgVectorStore expects this schema).
-- ${embeddingDimensions} is injected from spring.flyway.placeholders.embeddingDimensions
CREATE TABLE summary_embeddings (
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content   TEXT,
    metadata  JSONB,
    embedding vector(${embeddingDimensions})
);
CREATE INDEX idx_summary_embeddings_hnsw
    ON summary_embeddings USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_summary_embeddings_user
    ON summary_embeddings ((metadata->>'userId'));
CREATE INDEX idx_summary_embeddings_session
    ON summary_embeddings ((metadata->>'sessionId'));
