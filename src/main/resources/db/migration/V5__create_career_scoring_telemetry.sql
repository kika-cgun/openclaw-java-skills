CREATE TABLE career_scoring_telemetry (
    id                     UUID PRIMARY KEY,
    created_at             TIMESTAMP    NOT NULL,
    pending_offers         INTEGER      NOT NULL,
    selected_for_model     INTEGER      NOT NULL,
    auto_skipped_count     INTEGER      NOT NULL,
    cache_hit_count        INTEGER      NOT NULL,
    llm_scored_count       INTEGER      NOT NULL,
    estimated_input_tokens BIGINT       NOT NULL,
    estimated_output_tokens BIGINT      NOT NULL,
    model_used             VARCHAR(128) NOT NULL,
    score_source           VARCHAR(64)  NOT NULL,
    status                 VARCHAR(24)  NOT NULL
);

CREATE INDEX idx_career_scoring_telemetry_created_at
    ON career_scoring_telemetry(created_at DESC);
