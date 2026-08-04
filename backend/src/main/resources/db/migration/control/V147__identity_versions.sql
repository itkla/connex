ALTER TABLE workspace
    ADD COLUMN identity_version BIGINT NOT NULL DEFAULT 0 AFTER timezone;

ALTER TABLE organization
    ADD COLUMN identity_version BIGINT NOT NULL DEFAULT 0 AFTER slug;
