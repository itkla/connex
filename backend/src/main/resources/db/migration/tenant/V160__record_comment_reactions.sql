ALTER TABLE record_comment
    ADD UNIQUE KEY uq_record_comment_workspace_id (workspace_id, id);

CREATE TABLE record_comment_reaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id INT NOT NULL,
    comment_id BIGINT NOT NULL,
    user_id INT NOT NULL,
    reaction VARCHAR(24)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_record_comment_reaction
        (workspace_id, comment_id, user_id, reaction),
    CONSTRAINT fk_record_comment_reaction_comment
        FOREIGN KEY (workspace_id, comment_id)
        REFERENCES record_comment(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_record_comment_reaction
        CHECK (reaction IN (
            'thumbs_up', 'thumbs_down', 'heart', 'celebrate', 'eyes', 'laugh'
        )),
    INDEX idx_record_comment_reaction_comment
        (workspace_id, comment_id, reaction),
    INDEX idx_record_comment_reaction_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
