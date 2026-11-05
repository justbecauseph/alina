-- Per-character reputation. A character with no row has reputation 0.
CREATE TABLE character_state (
    character_id uuid        PRIMARY KEY,
    reputation   int         NOT NULL DEFAULT 0,
    updated_at   timestamptz NOT NULL
);

-- Commissions a character has accepted (active), turned in and cooling down, or turned in for good. An abandoned
-- commission is deleted, and so is one whose cooldown has passed.
CREATE TABLE commission_state (
    character_id   uuid        NOT NULL,
    commission_id text        NOT NULL,
    status         text        NOT NULL CHECK (status IN ('active', 'cooldown', 'completed')),
    progress       int         NOT NULL DEFAULT 0 CHECK (progress >= 0),
    accepted_at    timestamptz NOT NULL,
    completed_at   timestamptz,
    cooldown_until timestamptz,
    revision_id    bigint      NOT NULL,
    PRIMARY KEY (character_id, commission_id),
    CHECK (status <> 'cooldown' OR cooldown_until IS NOT NULL),
    CHECK (status <> 'completed' OR cooldown_until IS NULL)
);

-- Every operation, a turn-in or an achievement reward, keyed on its id. The row is written once, in the transaction
-- that applies the operation. Only step and note change afterwards.
CREATE TABLE operation (
    operation_id uuid        PRIMARY KEY,
    character_id uuid        NOT NULL,
    kind         text        NOT NULL CHECK (kind IN ('turn_in', 'achievement_reward')),
    subject_id   text        NOT NULL,
    tier         int         NOT NULL,
    revision_id  bigint      NOT NULL,
    world_id     text        NOT NULL,
    reward       jsonb       NOT NULL,
    reputation   int         NOT NULL,
    effect       jsonb       NOT NULL,
    step         text        NOT NULL CHECK (step IN ('recorded', 'settled', 'reconcile')),
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    note         text
);

CREATE INDEX operation_step_created ON operation (step, created_at);

-- One board per day. commission_ids keeps the board order.
CREATE TABLE board (
    board_date     date      PRIMARY KEY,
    revision_id    bigint    NOT NULL,
    seed           bigint    NOT NULL,
    commission_ids text[]    NOT NULL
);

-- Achievements a character has earned at least once. tier is the highest tier earned, 0 when the achievement has none.
CREATE TABLE achievement (
    character_id   uuid        NOT NULL,
    achievement_id text        NOT NULL,
    tier           int         NOT NULL,
    earn_count     int         NOT NULL CHECK (earn_count >= 0),
    last_earned_at timestamptz,
    available_at   timestamptz,
    updated_at     timestamptz NOT NULL,
    PRIMARY KEY (character_id, achievement_id)
);

-- Counted criteria of an achievement. A count of 0 is not stored: the row is deleted.
CREATE TABLE criterion_progress (
    character_id   uuid        NOT NULL,
    achievement_id text        NOT NULL,
    criterion      text        NOT NULL,
    count          int         NOT NULL CHECK (count > 0),
    updated_at     timestamptz NOT NULL,
    PRIMARY KEY (character_id, achievement_id, criterion)
);
