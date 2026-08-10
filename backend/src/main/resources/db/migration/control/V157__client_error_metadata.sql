-- Client-error diagnostics stay on the control plane because the support-bundle read spans an
-- organization's workspaces and must join only the control-plane workspace registry. The table
-- deliberately excludes error messages, diagnostic detail, and browser stacks.
CREATE TABLE client_error (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id INT NOT NULL,
    correlation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    digest VARCHAR(128) NULL,
    page_path VARCHAR(300) NULL,
    reported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_client_error_workspace_reported (workspace_id, reported_at),
    KEY idx_client_error_correlation_reported (correlation_id, reported_at),
    KEY idx_client_error_retention (reported_at),
    CONSTRAINT fk_client_error_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4
    COMMENT='Redacted client-error metadata retained for support diagnostics';
