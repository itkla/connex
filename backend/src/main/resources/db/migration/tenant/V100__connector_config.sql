-- ============================================================================
-- connector_config : a workspace's third-party marketing audience-sync connector
-- selection and settings. A connector pushes a frozen audience snapshot's eligible
-- members to an external marketing service (an addressable list). The push
-- credential (a connector API key) lives ENCRYPTED in the central secret store;
-- this table holds only its opaque reference and masked metadata. Owner/admin
-- managed (WORKSPACE_SETTINGS). One row per (workspace, connector).
-- ============================================================================

CREATE TABLE connector_config (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id        INT NOT NULL,
    connector           VARCHAR(32) NOT NULL,
    endpoint            VARCHAR(2048) NULL,
    external_list_id    VARCHAR(255) NULL,
    credential_ref      VARCHAR(255) NULL,
    credential_last4    VARCHAR(8) NULL,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_id       INT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_connector_config_connector
        CHECK (connector IN ('http_list')),
    UNIQUE KEY uq_connector_config_workspace_connector (workspace_id, connector),
    UNIQUE KEY uq_connector_config_workspace_id (workspace_id, id),
    INDEX idx_connector_config_created_by (created_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-workspace third-party audience-sync connector settings';
