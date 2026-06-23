-- Back up the database and review workspace ownership before applying manually.
-- Re-running is supported after a successful or partially completed run.

USE connexdb;

DELIMITER $$

DROP PROCEDURE IF EXISTS migrate_notification_system$$
CREATE PROCEDURE migrate_notification_system()
BEGIN
    DECLARE v_workspace_id INT DEFAULT NULL;
    DECLARE v_data_type VARCHAR(64) DEFAULT NULL;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'app_user'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'deal'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'task'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Required app_user, deal, or task table is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'app_user' AND column_name = 'timezone'
    ) THEN
        ALTER TABLE app_user
            ADD COLUMN timezone VARCHAR(64) NULL AFTER profile_picture_url;
    END IF;

    UPDATE app_user SET timezone = 'Asia/Tokyo' WHERE timezone IS NULL OR timezone = '';
    ALTER TABLE app_user
        MODIFY COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'UTC' COMMENT 'IANA timezone';

    CREATE TABLE IF NOT EXISTS workspace (
        id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Workspace ID',
        name        VARCHAR(128) NOT NULL COMMENT 'Workspace name',
        slug        VARCHAR(128) NOT NULL UNIQUE COMMENT 'Workspace slug',
        created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
        updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
    ) DEFAULT CHARSET=utf8mb4 COMMENT='Workspaces';

    CREATE TABLE IF NOT EXISTS workspace_member (
        workspace_id INT NOT NULL COMMENT 'Workspace ID',
        user_id      INT NOT NULL COMMENT 'Member User ID',
        role         VARCHAR(32) NOT NULL DEFAULT 'member' COMMENT 'Workspace role',
        created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Membership creation timestamp',
        PRIMARY KEY (workspace_id, user_id),
        CONSTRAINT fk_workspace_member_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
        CONSTRAINT fk_workspace_member_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
        INDEX idx_workspace_member_user (user_id, workspace_id)
    ) DEFAULT CHARSET=utf8mb4 COMMENT='Workspace memberships';

    INSERT INTO workspace (name, slug)
    SELECT 'Connex Workspace', 'default'
    WHERE NOT EXISTS (SELECT 1 FROM workspace WHERE slug = 'default');

    SELECT id INTO v_workspace_id
    FROM workspace
    WHERE slug = 'default'
    LIMIT 1;

    IF v_workspace_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Default workspace could not be resolved';
    END IF;

    IF EXISTS (
        SELECT 1 FROM workspace_member WHERE workspace_id <> v_workspace_id
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Non-default workspace memberships require manual review';
    END IF;

    INSERT INTO workspace_member (workspace_id, user_id, role)
    SELECT
        v_workspace_id,
        u.id,
        CASE WHEN u.id = (SELECT MIN(id) FROM app_user) THEN 'owner' ELSE 'member' END
    FROM app_user u
    WHERE NOT EXISTS (
        SELECT 1
        FROM workspace_member wm
        WHERE wm.user_id = u.id
    );

    IF EXISTS (
        SELECT user_id
        FROM workspace_member
        GROUP BY user_id
        HAVING COUNT(*) <> 1
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Each user must have exactly one workspace membership';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'deal' AND column_name = 'workspace_id'
    ) THEN
        ALTER TABLE deal ADD COLUMN workspace_id INT NULL AFTER id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'deal' AND column_name = 'owner_id'
    ) THEN
        ALTER TABLE deal ADD COLUMN owner_id INT NULL AFTER workspace_id;
    END IF;

    UPDATE deal SET workspace_id = v_workspace_id WHERE workspace_id IS NULL;

    IF EXISTS (
        SELECT 1
        FROM deal d
        LEFT JOIN workspace w ON w.id = d.workspace_id
        WHERE w.id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Deal workspace backfill contains invalid workspace IDs';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM deal d
        LEFT JOIN workspace_member wm
          ON wm.workspace_id = d.workspace_id
         AND wm.user_id = d.owner_id
        WHERE d.owner_id IS NOT NULL
          AND wm.user_id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Deal owners must be members of their workspace';
    END IF;

    ALTER TABLE deal
        MODIFY COLUMN workspace_id INT NOT NULL COMMENT 'Workspace ID',
        MODIFY COLUMN owner_id INT NULL COMMENT 'Owning workspace member User ID';

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'task' AND column_name = 'workspace_id'
    ) THEN
        ALTER TABLE task ADD COLUMN workspace_id INT NULL AFTER id;
    END IF;

    UPDATE task t
    LEFT JOIN deal d ON d.id = t.deal_id
    SET t.workspace_id = COALESCE(d.workspace_id, v_workspace_id)
    WHERE t.workspace_id IS NULL;

    IF EXISTS (
        SELECT 1
        FROM task t
        LEFT JOIN workspace w ON w.id = t.workspace_id
        WHERE w.id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Task workspace backfill contains invalid workspace IDs';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM task t
        LEFT JOIN deal d
          ON d.id = t.deal_id
         AND d.workspace_id = t.workspace_id
        WHERE t.deal_id IS NOT NULL
          AND d.id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Linked tasks and deals must share a workspace';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM task t
        LEFT JOIN workspace_member wm
          ON wm.workspace_id = t.workspace_id
         AND wm.user_id = t.assigned_to_id
        WHERE wm.user_id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Task assignees must be members of their workspace';
    END IF;

    ALTER TABLE task
        MODIFY COLUMN workspace_id INT NOT NULL COMMENT 'Workspace ID';

    SELECT data_type INTO v_data_type
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'deal'
      AND column_name = 'expected_close_date';

    IF v_data_type IS NULL OR v_data_type NOT IN ('date', 'datetime', 'timestamp') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected deal.expected_close_date type';
    END IF;

    IF v_data_type <> 'date' THEN
        ALTER TABLE deal MODIFY COLUMN expected_close_date DATE NULL COMMENT 'Expected close date';
    END IF;

    SET v_data_type = NULL;
    SELECT data_type INTO v_data_type
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'task'
      AND column_name = 'due_date';

    IF v_data_type IS NULL OR v_data_type NOT IN ('date', 'datetime', 'timestamp') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected task.due_date type';
    END IF;

    IF v_data_type <> 'date' THEN
        ALTER TABLE task MODIFY COLUMN due_date DATE NULL COMMENT 'Due date';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'deal' AND index_name = 'uq_deal_workspace_id'
    ) THEN
        ALTER TABLE deal ADD UNIQUE KEY uq_deal_workspace_id (workspace_id, id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'deal' AND index_name = 'idx_deal_workspace'
    ) THEN
        ALTER TABLE deal ADD INDEX idx_deal_workspace (workspace_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'deal' AND index_name = 'idx_deal_owner'
    ) THEN
        ALTER TABLE deal ADD INDEX idx_deal_owner (workspace_id, owner_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'deal' AND index_name = 'idx_deal_reminder'
    ) THEN
        ALTER TABLE deal ADD INDEX idx_deal_reminder (workspace_id, won, expected_close_date);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'task' AND index_name = 'idx_task_workspace'
    ) THEN
        ALTER TABLE task ADD INDEX idx_task_workspace (workspace_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'task' AND index_name = 'idx_task_workspace_assigned'
    ) THEN
        ALTER TABLE task ADD INDEX idx_task_workspace_assigned (workspace_id, assigned_to_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'task' AND index_name = 'idx_task_reminder'
    ) THEN
        ALTER TABLE task ADD INDEX idx_task_reminder (workspace_id, completed, due_date, assigned_to_id);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'task'
          AND constraint_name = 'fk_task_deal'
          AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE task DROP FOREIGN KEY fk_task_deal;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'task'
          AND constraint_name = 'fk_task_assigned'
          AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE task DROP FOREIGN KEY fk_task_assigned;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'deal'
          AND constraint_name = 'fk_deal_owner_member'
          AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE deal DROP FOREIGN KEY fk_deal_owner_member;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'task'
          AND constraint_name = 'fk_task_deal_workspace'
          AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE task DROP FOREIGN KEY fk_task_deal_workspace;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'deal'
          AND constraint_name = 'fk_deal_workspace' AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE deal
            ADD CONSTRAINT fk_deal_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'deal'
          AND constraint_name = 'fk_deal_owner' AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE deal
            ADD CONSTRAINT fk_deal_owner FOREIGN KEY (owner_id)
            REFERENCES app_user(id) ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'task'
          AND constraint_name = 'fk_task_workspace' AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE task
            ADD CONSTRAINT fk_task_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'task'
          AND constraint_name = 'fk_task_assigned_member' AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE task
            ADD CONSTRAINT fk_task_assigned_member FOREIGN KEY (workspace_id, assigned_to_id)
            REFERENCES workspace_member(workspace_id, user_id) ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'task'
          AND constraint_name = 'fk_task_deal' AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE task
            ADD CONSTRAINT fk_task_deal FOREIGN KEY (deal_id)
            REFERENCES deal(id) ON DELETE SET NULL;
    END IF;

    CREATE TABLE IF NOT EXISTS deal_collaborator (
        workspace_id INT NOT NULL COMMENT 'Workspace ID',
        deal_id      INT NOT NULL COMMENT 'Collaborated deal ID',
        user_id      INT NOT NULL COMMENT 'Collaborator User ID',
        created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Collaboration creation timestamp',
        PRIMARY KEY (workspace_id, deal_id, user_id),
        CONSTRAINT fk_deal_collaborator_deal FOREIGN KEY (workspace_id, deal_id) REFERENCES deal(workspace_id, id) ON DELETE CASCADE,
        CONSTRAINT fk_deal_collaborator_member FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_member(workspace_id, user_id) ON DELETE CASCADE,
        INDEX idx_deal_collaborator_user (workspace_id, user_id, deal_id)
    ) DEFAULT CHARSET=utf8mb4 COMMENT='Deal collaborators';

    CREATE TABLE IF NOT EXISTS notification (
        id                  INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Notification ID',
        workspace_id        INT NOT NULL COMMENT 'Workspace ID',
        recipient_id        INT NOT NULL COMMENT 'Recipient User ID',
        type                VARCHAR(64) NOT NULL COMMENT 'Stable notification type',
        category            VARCHAR(32) NOT NULL COMMENT 'Notification category',
        severity            VARCHAR(16) NOT NULL COMMENT 'Current notification severity',
        template_version    INT NOT NULL DEFAULT 1 COMMENT 'Message template version',
        title               VARCHAR(255) NOT NULL COMMENT 'Rendered title snapshot',
        body                TEXT NULL COMMENT 'Rendered body snapshot',
        actor_id            INT NULL COMMENT 'Actor User ID when applicable',
        actor_label         VARCHAR(255) NULL COMMENT 'Actor label snapshot',
        source_type         VARCHAR(64) NULL COMMENT 'Source entity type',
        source_id           INT NULL COMMENT 'Source entity ID',
        source_label        VARCHAR(255) NULL COMMENT 'Source entity label snapshot',
        context_type        VARCHAR(64) NULL COMMENT 'Context entity type',
        context_id          INT NULL COMMENT 'Context entity ID',
        context_label       VARCHAR(255) NULL COMMENT 'Context entity label snapshot',
        action_url          VARCHAR(2048) NULL COMMENT 'Internal notification action URL',
        data                JSON NULL COMMENT 'Structured rendering and action data',
        dedupe_key          VARCHAR(255) NOT NULL COMMENT 'Stable event identity within a recipient workspace',
        triggered_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Most recent material trigger timestamp',
        read_at             DATETIME NULL COMMENT 'Read timestamp',
        dismissed_at        DATETIME NULL COMMENT 'Dismissal timestamp',
        resolved_at         DATETIME NULL COMMENT 'Condition resolution timestamp',
        created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
        updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
        CONSTRAINT fk_notification_recipient_member FOREIGN KEY (workspace_id, recipient_id) REFERENCES workspace_member(workspace_id, user_id) ON DELETE CASCADE,
        CONSTRAINT fk_notification_actor FOREIGN KEY (actor_id) REFERENCES app_user(id) ON DELETE SET NULL,
        UNIQUE KEY uq_notification_dedupe (workspace_id, recipient_id, dedupe_key),
        INDEX idx_notification_inbox (workspace_id, recipient_id, dismissed_at, resolved_at, read_at, triggered_at),
        INDEX idx_notification_context (workspace_id, recipient_id, context_type, context_id, dismissed_at, resolved_at),
        INDEX idx_notification_source (workspace_id, source_type, source_id, type),
        INDEX idx_notification_actor (actor_id)
    ) DEFAULT CHARSET=utf8mb4 COMMENT='Durable user notifications';

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'notification'
          AND column_name = 'dedupe_key'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Existing notification table is missing dedupe_key';
    END IF;

    IF EXISTS (SELECT 1 FROM notification WHERE dedupe_key IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Notification rows with null dedupe keys require manual review';
    END IF;

    ALTER TABLE notification
        MODIFY COLUMN dedupe_key VARCHAR(255) NOT NULL COMMENT 'Stable event identity within a recipient workspace';

    CREATE TABLE IF NOT EXISTS notification_preference (
        id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Notification preference ID',
        user_id     INT NOT NULL COMMENT 'User ID',
        type        VARCHAR(64) NOT NULL COMMENT 'Notification type or wildcard',
        channel     VARCHAR(32) NOT NULL COMMENT 'Delivery channel',
        enabled     BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Whether delivery is enabled',
        created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
        updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
        CONSTRAINT fk_notification_preference_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
        UNIQUE KEY uq_notification_preference (user_id, type, channel)
    ) DEFAULT CHARSET=utf8mb4 COMMENT='Per-user notification channel preferences';
END$$

CALL migrate_notification_system()$$
DROP PROCEDURE migrate_notification_system$$

DELIMITER ;
