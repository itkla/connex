ALTER TABLE person
    ADD COLUMN suspended_at DATETIME NULL AFTER intro_excluded,
    ADD COLUMN provision_ceased_at DATETIME NULL AFTER suspended_at;
