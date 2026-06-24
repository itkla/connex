-- ============================================================================
-- Let a task outlive its assignee's membership. The composite member FK can't
-- be ON DELETE SET NULL (its workspace_id leg is NOT NULL), so drop it, make
-- assigned_to_id nullable, and FK it to app_user with SET NULL. Member removal
-- unassigns the member's tasks explicitly; assignee-must-be-a-member is then a
-- service-level check on create/update.
-- ============================================================================

ALTER TABLE task
    DROP FOREIGN KEY fk_task_assigned_member,
    MODIFY assigned_to_id INT NULL COMMENT 'Assigned to User ID (null = unassigned)',
    ADD CONSTRAINT fk_task_assigned_to FOREIGN KEY (assigned_to_id) REFERENCES app_user(id) ON DELETE SET NULL;
