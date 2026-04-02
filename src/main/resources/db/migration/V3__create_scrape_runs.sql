CREATE TABLE scrape_runs (
    id               UUID PRIMARY KEY,
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP,
    new_offers_count INT,
    status           VARCHAR(20)
);
