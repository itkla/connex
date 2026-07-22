CREATE TABLE task_board_lock (
    workspace_id INT NOT NULL,
    PRIMARY KEY (workspace_id)
) ENGINE=InnoDB COMMENT='Per-workspace lock root serializing task board positions';

UPDATE task t
JOIN (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY workspace_id, status ORDER BY position, id
    ) - 1 AS rn
    FROM task
) ordered_task ON ordered_task.id = t.id
SET t.position = ordered_task.rn;
