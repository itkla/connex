ALTER TABLE workflow_run
    ADD COLUMN launch_inputs_json JSON NULL AFTER attribution_user_id,
    ADD CONSTRAINT chk_workflow_run_launch_inputs
        CHECK (launch_inputs_json IS NULL OR (
            JSON_TYPE(launch_inputs_json) = 'OBJECT'
            AND OCTET_LENGTH(launch_inputs_json) <= 16384));

ALTER TABLE workflow_step_run
    ADD COLUMN action_outputs_json JSON NULL AFTER action_reference_id,
    ADD CONSTRAINT chk_workflow_step_action_outputs
        CHECK (action_outputs_json IS NULL OR (
            JSON_TYPE(action_outputs_json) = 'OBJECT'
            AND OCTET_LENGTH(action_outputs_json) <= 4096));
