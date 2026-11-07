-- Several boards, each with its own draw for each day. A board row written before this migration is the implicit board,
-- alina:board. board_id has no default, so every insert names its board.
ALTER TABLE board ADD COLUMN board_id text NOT NULL DEFAULT 'alina:board';
ALTER TABLE board ALTER COLUMN board_id DROP DEFAULT;
ALTER TABLE board DROP CONSTRAINT board_pkey;
ALTER TABLE board ADD PRIMARY KEY (board_date, board_id);
