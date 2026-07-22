-- ============================================================================
-- Multi-provider BYOP AI configuration (#425/#381). Provider-specific endpoint
-- and deployment metadata remains organization-scoped while credential bundles
-- continue to live only in the central secret store.
-- ============================================================================

ALTER TABLE ai_provider_config
    DROP CHECK ck_ai_provider_config_provider,
    DROP CHECK ck_ai_provider_config_concrete_region,
    MODIFY COLUMN region VARCHAR(64) NULL COMMENT 'Validated provider region or location',
    ADD COLUMN endpoint VARCHAR(512) NULL COMMENT 'Validated provider base endpoint' AFTER region,
    ADD COLUMN api_version VARCHAR(32) NULL COMMENT 'Provider API version' AFTER endpoint,
    ADD COLUMN deployment VARCHAR(128) NULL COMMENT 'Provider deployment name' AFTER api_version,
    ADD COLUMN project_id VARCHAR(128) NULL COMMENT 'Provider project identifier' AFTER deployment,
    ADD COLUMN allow_internal_endpoint BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether private endpoint addresses are allowed' AFTER project_id,
    ADD CONSTRAINT ck_ai_provider_config_provider
        CHECK (provider IN ('bedrock', 'azure_openai', 'vertex', 'openai_compatible'));
