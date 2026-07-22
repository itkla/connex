ALTER TABLE task
    ADD UNIQUE KEY uq_task_workspace_id (workspace_id, id);
