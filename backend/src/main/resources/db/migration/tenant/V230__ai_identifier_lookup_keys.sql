-- Nullable derived aliases preserve older writers and the tenant/V228 rollback floor.
-- TEXT holds the NFKC expansion of 255 source code points (up to 18 code points each).
-- INSTANT fails instead of silently rebuilding a core CRM table. Freshness is checked inline
-- against the version and a byte-exact name snapshot; old-binary renames need no trigger.
ALTER TABLE person
    ADD COLUMN ai_identifier_literal_key TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN ai_identifier_label_key TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN ai_identifier_source_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN ai_identifier_key_version SMALLINT UNSIGNED NULL,
    ALGORITHM=INSTANT;

ALTER TABLE company
    ADD COLUMN ai_identifier_literal_key TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN ai_identifier_label_key TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN ai_identifier_source_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN ai_identifier_key_version SMALLINT UNSIGNED NULL,
    ALGORITHM=INSTANT;

ALTER TABLE deal
    ADD COLUMN ai_identifier_literal_key TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN ai_identifier_label_key TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN ai_identifier_source_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN ai_identifier_key_version SMALLINT UNSIGNED NULL,
    ALGORITHM=INSTANT;

