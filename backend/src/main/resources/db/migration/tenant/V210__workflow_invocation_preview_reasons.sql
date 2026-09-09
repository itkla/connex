ALTER TABLE workflow_invocation_record
    DROP CHECK chk_workflow_invocation_record_preview,
    ADD CONSTRAINT chk_workflow_invocation_record_preview
        CHECK (
            (preview_status = 'ready' AND preview_reason_code IS NULL)
            OR (
                preview_status = 'skipped'
                AND preview_reason_code IN (
                    'record_not_visible', 'record_not_found',
                    'record_type_mismatch', 'action_permission_missing',
                    'actor_unavailable', 'actor_inactive',
                    'configuration_missing', 'record_unavailable',
                    'entry_condition_not_matched', 'active_run_exists',
                    'cooldown_active'
                )
            )
        );
