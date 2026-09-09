ALTER TABLE workflow_run
    DROP CHECK chk_workflow_run_status_reason,
    ADD CONSTRAINT chk_workflow_run_status_reason CHECK (
        status_reason IS NULL OR status IN ('skipped', 'stopped')
    );
