-- Explicit INSTANT refuses a table rebuild even when old_alter_table is enabled.
-- Metadata locks and partial-DDL recovery requirements are documented in docs/UPGRADING.md.
ALTER TABLE person
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT, LOCK=DEFAULT;

-- Include every person writer, including maintenance that preserves updated_at.
CREATE TRIGGER trg_person_edit_version
BEFORE UPDATE ON person
FOR EACH ROW
    SET NEW.version = OLD.version + 1;

-- FK SET NULL actions bypass UPDATE triggers. Detach through the same indexed parent key
-- before the existing company cascade so surviving contacts advance their version too.
CREATE TRIGGER trg_company_delete_person_edit_version
BEFORE DELETE ON company
FOR EACH ROW
    UPDATE person SET company_id = NULL WHERE company_id = OLD.id;
