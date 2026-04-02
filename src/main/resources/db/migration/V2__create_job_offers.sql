CREATE TABLE job_offers (
    id           UUID PRIMARY KEY,
    source       VARCHAR(50)  NOT NULL,
    external_id  VARCHAR(64)  NOT NULL UNIQUE,
    title        VARCHAR(255),
    company      VARCHAR(255),
    location     VARCHAR(255),
    url          TEXT,
    description  TEXT,
    score        VARCHAR(20)  NOT NULL DEFAULT 'PENDING_SCORE',
    score_reason TEXT,
    found_at     TIMESTAMP,
    sent_at      TIMESTAMP
);

CREATE INDEX idx_job_offers_score   ON job_offers(score);
CREATE INDEX idx_job_offers_sent_at ON job_offers(sent_at);
