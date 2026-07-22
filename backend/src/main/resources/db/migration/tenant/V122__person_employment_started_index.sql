ALTER TABLE person_employment
    ADD INDEX idx_person_employment_started (workspace_id, started_at, person_id);
