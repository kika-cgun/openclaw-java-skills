CREATE TABLE user_profile (
    id          BIGSERIAL PRIMARY KEY,
    stack       TEXT,
    level       TEXT,
    locations   TEXT,
    preferences TEXT,
    updated_at  TIMESTAMP
);
