UPDATE saved_view
SET config_json = CASE
    WHEN JSON_TYPE(config_json) = 'OBJECT'
        THEN JSON_SET(config_json, '$.version', 1)
    ELSE JSON_OBJECT('version', 1, 'legacyConfig', config_json)
END,
    updated_at = updated_at;

ALTER TABLE saved_view
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'private' AFTER config_json,
    ADD CONSTRAINT chk_saved_view_visibility
        CHECK (visibility IN ('private', 'workspace')),
    ADD CONSTRAINT chk_saved_view_config_object
        CHECK (JSON_TYPE(config_json) = 'OBJECT'),
    ADD CONSTRAINT chk_saved_view_position
        CHECK (position >= 0),
    ADD UNIQUE KEY uq_saved_view_workspace_id
        (workspace_id, id),
    ADD UNIQUE KEY uq_saved_view_workspace_id_type
        (workspace_id, id, record_type),
    ADD INDEX idx_saved_view_visible_type
        (workspace_id, record_type, visibility, position, id);

CREATE TABLE saved_view_pin (
    workspace_id  INT NOT NULL,
    user_id       INT NOT NULL,
    saved_view_id INT NOT NULL,
    position      INT NOT NULL DEFAULT 0,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (workspace_id, user_id, saved_view_id),
    CONSTRAINT chk_saved_view_pin_position CHECK (position >= 0),
    CONSTRAINT fk_saved_view_pin_view
        FOREIGN KEY (workspace_id, saved_view_id)
        REFERENCES saved_view(workspace_id, id) ON DELETE CASCADE,
    INDEX idx_saved_view_pin_order
        (workspace_id, user_id, position, saved_view_id),
    INDEX idx_saved_view_pin_target
        (workspace_id, saved_view_id),
    INDEX idx_saved_view_pin_user
        (user_id, workspace_id)
);

CREATE TABLE saved_view_default (
    workspace_id  INT NOT NULL,
    user_id       INT NOT NULL,
    record_type   VARCHAR(16) NOT NULL,
    saved_view_id INT NOT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (workspace_id, user_id, record_type),
    CONSTRAINT fk_saved_view_default_view
        FOREIGN KEY (workspace_id, saved_view_id, record_type)
        REFERENCES saved_view(workspace_id, id, record_type)
        ON DELETE CASCADE,
    INDEX idx_saved_view_default_target
        (workspace_id, saved_view_id),
    INDEX idx_saved_view_default_user
        (user_id, workspace_id)
);
