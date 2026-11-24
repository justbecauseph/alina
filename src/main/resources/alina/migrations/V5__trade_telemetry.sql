-- Indexes for read-only trade economy summaries and recent trade operation listings.
CREATE INDEX operation_kind_created ON operation (kind, created_at DESC, operation_id DESC);
CREATE INDEX trade_budget_day_shop ON trade_budget (trade_day, shop_id);
