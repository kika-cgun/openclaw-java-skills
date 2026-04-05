CREATE TABLE career_scoring_cache (
    id            UUID PRIMARY KEY,
    cache_key     VARCHAR(64)   NOT NULL UNIQUE,
    offer_id      UUID          NOT NULL,
    profile_hash  VARCHAR(64)   NOT NULL,
    model_version VARCHAR(128)  NOT NULL,
    score         VARCHAR(20)   NOT NULL,
    reason        TEXT,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL
);

CREATE INDEX idx_career_scoring_cache_offer_id ON career_scoring_cache(offer_id);
