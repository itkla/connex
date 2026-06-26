-- ============================================================================
-- Custom fields (#56). A per-workspace, per-entity-type catalog
-- (custom_field_definition) plus polymorphic values (custom_field_value,
-- modeled on attachment: workspace_id is stamped from the ACTIVE workspace and
-- the owner is referenced by (entity_type, entity_id) with no FK, so values
-- overlay shared records per the viewing workspace). The only composite FK is
-- value -> definition, which makes a value referencing another workspace's
-- field structurally impossible.
-- ============================================================================

CREATE TABLE custom_field_definition (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL,
    entity_type   VARCHAR(16)  NOT NULL COMMENT 'company | person | deal',
    field_key     VARCHAR(64)  NOT NULL COMMENT 'stable machine key',
    label         VARCHAR(128) NOT NULL,
    field_type    VARCHAR(16)  NOT NULL COMMENT 'text|textarea|number|date|boolean|select|url',
    options_json  JSON NULL COMMENT 'choices for select fields',
    required      BOOLEAN NOT NULL DEFAULT FALSE,
    position      INT NOT NULL DEFAULT 0,
    archived      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_cfd_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    UNIQUE KEY uq_cfd_workspace_entity_key (workspace_id, entity_type, field_key),
    UNIQUE KEY uq_cfd_workspace_id (workspace_id, id),
    INDEX idx_cfd_workspace_entity (workspace_id, entity_type)
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-workspace custom field definitions';

CREATE TABLE custom_field_value (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL COMMENT 'viewing/active workspace (overlay anchor)',
    definition_id INT NOT NULL,
    entity_type   VARCHAR(16) NOT NULL COMMENT 'company | person | deal',
    entity_id     INT NOT NULL COMMENT 'polymorphic owner, no FK (see attachment)',
    value_text    TEXT NULL,
    value_number  DECIMAL(20,4) NULL,
    value_date    DATETIME NULL,
    value_bool    TINYINT(1) NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_cfv_definition FOREIGN KEY (workspace_id, definition_id)
        REFERENCES custom_field_definition(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_cfv_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    UNIQUE KEY uq_cfv_def_entity (workspace_id, definition_id, entity_id),
    INDEX idx_cfv_entity (workspace_id, entity_type, entity_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Custom field values (per-workspace overlay on records)';
