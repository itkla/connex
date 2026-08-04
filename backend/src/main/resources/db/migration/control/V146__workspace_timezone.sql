-- Existing workspaces deliberately retain a null override so callers can fall back to actor timezone.
ALTER TABLE workspace
    ADD COLUMN timezone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER slug;
