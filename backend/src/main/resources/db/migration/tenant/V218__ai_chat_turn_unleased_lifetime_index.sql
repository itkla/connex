-- The assistant-turn lifetime sweep discovers workspaces holding a non-terminal turn that no run
-- lease covers and that has outlived the absolute turn lifetime. That discovery runs once every
-- thirty seconds on every instance, for every pinned catalog, and it carries a workspace cursor
-- rather than a workspace predicate, so none of the existing indexes can be seeked for it: every
-- one of them leads with workspace_id, and a cursor range on the leading column leaves the
-- selective status and updated_at predicates as per-entry conditions. In the steady state the
-- probe returns nothing, so the page limit never short-circuits and the scan runs to the end of an
-- index that grows one entry per assistant turn ever asked.
--
-- Leading with status and updated_at turns the probe into two short ranges - one per non-terminal
-- status, bounded by the lifetime cutoff - and leaves workspace_id trailing as the cursor and as
-- the distinct key. Cost then follows the outstanding recovery work rather than the tenant's whole
-- assistant history, and a tick with nothing to recover reads no entries at all.
--
-- Strictly additive: no column, constraint, or status value changes, so a binary predating this
-- migration is unaffected and a rollback that retains it only restores the scan.
CREATE INDEX idx_ai_chat_turn_unleased_lifetime
    ON ai_chat_turn (status, updated_at, workspace_id);
