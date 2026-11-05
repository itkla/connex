ALTER TABLE deal
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- A database-owned counter includes legacy binaries, maintenance, bulk and targeted writers,
-- even writes that deliberately preserve updated_at or occur within the same clock tick.
CREATE TRIGGER trg_deal_edit_version
BEFORE UPDATE ON deal
FOR EACH ROW
    SET NEW.version = OLD.version + 1;

-- MySQL FK SET NULL actions bypass UPDATE triggers. Detach through the same indexed parent
-- key before the existing company cascade so those surviving deals also advance their version.
CREATE TRIGGER trg_company_delete_deal_edit_version
BEFORE DELETE ON company
FOR EACH ROW
    UPDATE deal SET company_id = NULL WHERE company_id = OLD.id;
