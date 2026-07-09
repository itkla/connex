ALTER TABLE appi_incident
    ADD CONSTRAINT ck_appi_incident_status CHECK (status IN ('triage', 'contained', 'notifiable', 'notified', 'closed')),
    ADD CONSTRAINT ck_appi_incident_severity CHECK (severity IN ('undetermined', 'low', 'medium', 'high', 'critical')),
    ADD CONSTRAINT ck_appi_incident_window CHECK (occurred_from IS NULL OR occurred_to IS NULL OR occurred_from <= occurred_to);
