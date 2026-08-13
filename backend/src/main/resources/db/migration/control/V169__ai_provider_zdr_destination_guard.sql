-- Older binaries omit the ZDR columns from their provider-config upsert. During a rolling deploy,
-- this trigger invalidates a retained attestation when such a writer changes the provider
-- destination, while allowing a current writer to submit a deliberate attestation transition.
DELIMITER //
CREATE TRIGGER trg_ai_provider_config_zdr_destination_guard
BEFORE UPDATE ON ai_provider_config
FOR EACH ROW
BEGIN
    IF (
            NOT (BINARY OLD.provider <=> BINARY NEW.provider)
            OR NOT (BINARY OLD.endpoint <=> BINARY NEW.endpoint)
            OR NOT (BINARY OLD.deployment <=> BINARY NEW.deployment)
            OR NOT (BINARY OLD.project_id <=> BINARY NEW.project_id)
            OR NOT (BINARY OLD.region <=> BINARY NEW.region)
            OR NOT (BINARY OLD.model_id <=> BINARY NEW.model_id)
            OR NOT (OLD.allow_internal_endpoint <=> NEW.allow_internal_endpoint)
        )
        AND OLD.zero_data_retention_attested <=> NEW.zero_data_retention_attested
        AND OLD.zdr_attested_by_user_id <=> NEW.zdr_attested_by_user_id
        AND OLD.zdr_attested_at <=> NEW.zdr_attested_at
        AND OLD.zdr_attestation_version <=> NEW.zdr_attestation_version THEN
        SET NEW.zero_data_retention_attested = FALSE;
        SET NEW.zdr_attested_by_user_id = NULL;
        SET NEW.zdr_attested_at = NULL;
        SET NEW.zdr_attestation_version = NULL;
    END IF;
END//
DELIMITER ;
