-- Email and SMS shared one secret slot; ownership of its last value is unrecoverable.
-- Operators must re-enter each channel's key before re-enabling the affected configs.
UPDATE delivery_provider_config
SET credential_ref = NULL,
    credential_last4 = NULL,
    enabled = FALSE,
    config_generation = config_generation + 1
WHERE credential_ref IS NOT NULL;
