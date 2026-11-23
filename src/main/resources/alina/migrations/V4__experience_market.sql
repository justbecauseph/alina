-- The shared pool every experience broker trades against. One row. Its reserves are the exact sum of the seed and every
-- delta applied, so they are not bounded here: a reserve may pass below 1 and the server clamps what it reads.
CREATE TABLE experience_market (
    id            smallint    PRIMARY KEY CHECK (id = 1),
    points        bigint      NOT NULL,
    aur           bigint      NOT NULL,
    restocked_day date        NOT NULL,
    updated_at    timestamptz NOT NULL
);

-- The reserves after each change of the pool, newest last, for the rate chart. Pruned to the newest rows.
CREATE TABLE experience_market_tick (
    id     bigserial   PRIMARY KEY,
    points bigint      NOT NULL,
    aur    bigint      NOT NULL,
    at     timestamptz NOT NULL
);
