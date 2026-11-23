-- NPC shop trades. The operation kind check admits trade.
ALTER TABLE operation DROP CONSTRAINT operation_kind_check;
ALTER TABLE operation ADD CONSTRAINT operation_kind_check CHECK (kind IN ('turn_in', 'achievement_reward', 'trade'));

-- The Aur a shop has paid a character on a day. A day's row is one budget, so a trade adds to it.
CREATE TABLE trade_budget (
    character_id uuid        NOT NULL,
    shop_id      text        NOT NULL,
    trade_day    date        NOT NULL,
    aur_paid     integer     NOT NULL CHECK (aur_paid >= 0),
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (character_id, shop_id, trade_day)
);

-- How many of an offer a character has bought, under a period: an ISO date for a per-day limit, or character for a
-- per-character limit.
CREATE TABLE trade_purchase (
    character_id uuid        NOT NULL,
    shop_id      text        NOT NULL,
    offer_id     text        NOT NULL,
    period       text        NOT NULL,
    count        integer     NOT NULL CHECK (count >= 0),
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (character_id, shop_id, offer_id, period)
);
