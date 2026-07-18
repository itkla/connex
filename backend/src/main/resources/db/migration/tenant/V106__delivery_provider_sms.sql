-- ============================================================================
-- Widen delivery_provider_config.provider to admit the SMS HTTP gateway adapter
-- (sms_http), so a workspace can configure an SMS provider on its (workspace,
-- channel='sms') row. The channel CHECK already admits 'sms'; only the provider
-- vocabulary needs widening. Forward-only; the credential/webhook secret flow is
-- unchanged and continues to hold only opaque secret references.
-- ============================================================================

ALTER TABLE delivery_provider_config
    DROP CONSTRAINT chk_delivery_provider_config_provider,
    ADD CONSTRAINT chk_delivery_provider_config_provider
        CHECK (provider IN ('smtp', 'http_esp', 'sms_http'));
