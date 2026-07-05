-- Server-enforced note visibility. Preserves today's implicit rule: a note with
-- no person/deal was private to its author, and anything attached to a record was
-- visible to the whole workspace.
ALTER TABLE note
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'workspace' AFTER deal_id;

UPDATE note SET visibility = 'private' WHERE person_id IS NULL AND deal_id IS NULL;
