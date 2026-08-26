-- Old application instances omit this marker. The new application rejects every version-0 pending
-- membership until a current authorized built-in role assignment refreshes it. Custom role overlays
-- must preserve version 0 because their deletion restores the historical built-in role. This
-- security cutover requires the coordinated all-backends-down deployment documented in UPGRADING.md.
ALTER TABLE workspace_member
    ADD COLUMN grant_authorization_version TINYINT NOT NULL DEFAULT 0
        COMMENT 'Locked role-grant authorization protocol version for pending membership' AFTER status;
