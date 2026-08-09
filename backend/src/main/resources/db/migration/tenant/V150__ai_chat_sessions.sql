-- Durable assistant chat state is tenant-plane data. User and workspace identifiers are
-- deliberately bare integers because app_user and workspace live on the control plane.

CREATE TABLE ai_chat_session (
    workspace_id       INT NOT NULL,
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    created_by_user_id INT NOT NULL,
    title              VARCHAR(200)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    visibility         VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'private',
    status             VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'active',
    last_message_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    archived_at        DATETIME(6) NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_ai_chat_session_visibility
        CHECK (visibility IN ('private', 'shared')),
    CONSTRAINT chk_ai_chat_session_status
        CHECK (status IN ('active', 'archived')),
    CONSTRAINT chk_ai_chat_session_archive_state
        CHECK ((status = 'active' AND archived_at IS NULL)
            OR (status = 'archived' AND archived_at IS NOT NULL)),
    UNIQUE KEY uq_ai_chat_session_workspace_id (workspace_id, id),
    INDEX idx_ai_chat_session_owner_last_message
        (workspace_id, created_by_user_id, last_message_at, id),
    INDEX idx_ai_chat_session_last_message
        (workspace_id, last_message_at, id),
    INDEX idx_ai_chat_session_created_by
        (created_by_user_id, workspace_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Durable tenant-scoped assistant chat sessions';

-- Every child edge carries workspace_id into the foreign key. This makes a mismatched
-- workspace/session or workspace/message pair fail in MySQL even if application checks regress.
CREATE TABLE ai_chat_session_participant (
    workspace_id INT NOT NULL,
    session_id   INT NOT NULL,
    user_id      INT NOT NULL,
    role         VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'member',
    joined_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, session_id, user_id),
    CONSTRAINT chk_ai_chat_session_participant_role
        CHECK (role IN ('owner', 'member')),
    CONSTRAINT fk_ai_chat_session_participant_session
        FOREIGN KEY (workspace_id, session_id)
        REFERENCES ai_chat_session(workspace_id, id) ON DELETE CASCADE,
    INDEX idx_ai_chat_session_participant_user
        (workspace_id, user_id, session_id),
    INDEX idx_ai_chat_session_participant_control_user
        (user_id, workspace_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Explicit participants allowed to access shared assistant sessions';

CREATE TABLE ai_chat_message (
    workspace_id   INT NOT NULL,
    id             INT AUTO_INCREMENT PRIMARY KEY,
    session_id     INT NOT NULL,
    seq            INT NOT NULL,
    author_kind    VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    author_user_id INT NULL,
    content        TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    structured_json JSON NULL,
    input_tokens    INT NULL,
    output_tokens   INT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_ai_chat_message_seq CHECK (seq > 0),
    CONSTRAINT chk_ai_chat_message_author_kind
        CHECK (author_kind IN ('user', 'assistant', 'system', 'tool')),
    CONSTRAINT chk_ai_chat_message_content
        CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 16000),
    CONSTRAINT fk_ai_chat_message_session
        FOREIGN KEY (workspace_id, session_id)
        REFERENCES ai_chat_session(workspace_id, id) ON DELETE CASCADE,
    UNIQUE KEY uq_ai_chat_message_workspace_id (workspace_id, id),
    UNIQUE KEY uq_ai_chat_message_session_seq (workspace_id, session_id, seq),
    INDEX idx_ai_chat_message_author_user
        (author_user_id, workspace_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Gap-free ordered messages within an assistant session';

-- A tool call reaches its session through ai_chat_message; session_id is not denormalized here.
CREATE TABLE ai_chat_tool_call (
    workspace_id       INT NOT NULL,
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    message_id         INT NOT NULL,
    tool_name          VARCHAR(128)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    status             VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'proposed',
    arguments_json     JSON NULL,
    result_json        JSON NULL,
    executed_by_user_id INT NULL,
    executed_at        DATETIME(6) NULL,
    idempotency_key    VARCHAR(64)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_ai_chat_tool_call_status
        CHECK (status IN ('proposed', 'approved', 'rejected', 'executed', 'failed')),
    CONSTRAINT fk_ai_chat_tool_call_message
        FOREIGN KEY (workspace_id, message_id)
        REFERENCES ai_chat_message(workspace_id, id) ON DELETE CASCADE,
    UNIQUE KEY uq_ai_chat_tool_call_idempotency
        (workspace_id, idempotency_key),
    INDEX idx_ai_chat_tool_call_message
        (workspace_id, message_id, id),
    INDEX idx_ai_chat_tool_call_executed_by
        (executed_by_user_id, workspace_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Durable tool-call records associated with assistant messages';

CREATE TABLE ai_chat_turn (
    workspace_id        INT NOT NULL,
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    session_id          INT NOT NULL,
    requested_by_user_id INT NOT NULL,
    status              VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'queued',
    terminal_reason     VARCHAR(1000)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_ai_chat_turn_status
        CHECK (status IN ('queued', 'running', 'resolved', 'failed', 'timed_out')),
    CONSTRAINT fk_ai_chat_turn_session
        FOREIGN KEY (workspace_id, session_id)
        REFERENCES ai_chat_session(workspace_id, id) ON DELETE CASCADE,
    INDEX idx_ai_chat_turn_session
        (workspace_id, session_id, created_at, id),
    INDEX idx_ai_chat_turn_status
        (workspace_id, status, updated_at, id),
    INDEX idx_ai_chat_turn_requested_by
        (requested_by_user_id, workspace_id)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Durable queued, in-flight, and terminal assistant turn state';
