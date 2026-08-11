CREATE TABLE record_comment_thread (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id INT NOT NULL,
    target_type VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    target_id INT NOT NULL,
    created_by_user_id INT NOT NULL,
    state VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'open',
    resolved_by_user_id INT NULL,
    resolved_at DATETIME(6) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- Composite identity so child rows can carry a workspace-coherent FK,
    -- matching the deal/campaign/ai_chat parent-table pattern.
    UNIQUE KEY uq_record_comment_thread_workspace_id (workspace_id, id),
    CONSTRAINT chk_record_comment_thread_target_type
        CHECK (target_type IN ('person', 'company', 'deal')),
    CONSTRAINT chk_record_comment_thread_state
        CHECK (state IN ('open', 'resolved')),
    INDEX idx_record_comment_thread_target
        (workspace_id, target_type, target_id, state, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE record_comment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id INT NOT NULL,
    thread_id BIGINT NOT NULL,
    author_user_id INT NOT NULL,
    content TEXT NULL,
    client_token CHAR(36)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    deleted_by_user_id INT NULL,
    PRIMARY KEY (id),
    -- Composite FK keeps a comment's workspace equal to its thread's workspace,
    -- so no erroneous write can create a cross-workspace parent/child pair.
    CONSTRAINT fk_record_comment_thread
        FOREIGN KEY (workspace_id, thread_id)
        REFERENCES record_comment_thread(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_record_comment_content
        CHECK (content IS NULL OR CHAR_LENGTH(content) BETWEEN 1 AND 5000),
    UNIQUE KEY uq_record_comment_client_token (workspace_id, client_token),
    INDEX idx_record_comment_thread (workspace_id, thread_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
