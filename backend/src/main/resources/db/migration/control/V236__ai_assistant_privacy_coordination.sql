CREATE TABLE ai_assistant_privacy_fence (
    organization_id INT NOT NULL,
    generation BIGINT UNSIGNED NOT NULL,
    bootstrap_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bootstrap_completed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id),
    UNIQUE KEY uq_ai_assistant_privacy_bootstrap (bootstrap_operation_id),
    CONSTRAINT chk_ai_assistant_privacy_generation
        CHECK (generation BETWEEN 1 AND 9223372036854775807)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_assistant_privacy_operation (
    organization_id INT NOT NULL,
    operation_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cutoff_generation BIGINT UNSIGNED NOT NULL,
    invalidated_generation BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_fingerprint BINARY(32) NOT NULL,
    dsr_request_id BIGINT NULL,
    control_acceptance_audit_id INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (organization_id, operation_key),
    UNIQUE KEY uq_ai_assistant_privacy_operation_generation
        (organization_id, invalidated_generation),
    CONSTRAINT chk_ai_assistant_privacy_operation_scope
        CHECK (content_scope = 'organization_assistant_holdings_v2'),
    CONSTRAINT chk_ai_assistant_privacy_operation_generation
        CHECK (cutoff_generation < 9223372036854775807
            AND invalidated_generation = cutoff_generation + 1),
    CONSTRAINT chk_ai_assistant_privacy_operation_reason
        CHECK (reason IN ('bootstrap', 'restriction', 'dsr',
                          'offboarding', 'account_erasure')),
    CONSTRAINT chk_ai_assistant_privacy_operation_dsr
        CHECK ((reason = 'dsr' AND dsr_request_id IS NOT NULL)
            OR (reason <> 'dsr' AND dsr_request_id IS NULL
                AND control_acceptance_audit_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_assistant_privacy_operation_workspace (
    organization_id INT NOT NULL,
    operation_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id INT NOT NULL,
    placement_catalog VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'pending',
    verified_work_revision BIGINT UNSIGNED NULL,
    verified_at DATETIME(6) NULL,
    PRIMARY KEY (organization_id, operation_key, workspace_id),
    INDEX idx_ai_assistant_privacy_workspace_pending
        (state, organization_id, workspace_id),
    CONSTRAINT fk_ai_assistant_privacy_workspace_operation
        FOREIGN KEY (organization_id, operation_key)
        REFERENCES ai_assistant_privacy_operation (organization_id, operation_key)
        ON DELETE RESTRICT,
    CONSTRAINT chk_ai_assistant_privacy_workspace_state
        CHECK ((state = 'pending' AND verified_at IS NULL)
            OR (state = 'verified' AND verified_at IS NOT NULL
                AND verified_work_revision IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ai_assistant_privacy_fence
    (organization_id, generation, bootstrap_operation_id)
SELECT id, 1, UUID()
FROM organization;

INSERT INTO ai_assistant_privacy_operation
    (organization_id, operation_key, content_scope,
     cutoff_generation, invalidated_generation, reason, operation_fingerprint)
SELECT organization_id, bootstrap_operation_id,
       'organization_assistant_holdings_v2',
       0, 1, 'bootstrap',
       UNHEX(SHA2(CONCAT('assistant-bootstrap-v1|',
                        organization_id, '|', bootstrap_operation_id), 256))
FROM ai_assistant_privacy_fence;

INSERT INTO ai_assistant_privacy_operation_workspace
    (organization_id, operation_key, workspace_id)
SELECT w.org_id, f.bootstrap_operation_id, w.id
FROM workspace w
JOIN ai_assistant_privacy_fence f ON f.organization_id = w.org_id;
