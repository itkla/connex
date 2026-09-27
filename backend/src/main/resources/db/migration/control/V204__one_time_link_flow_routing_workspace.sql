-- Tenant-plane sources (document acceptance) need a routing hint so a token-free endpoint can
-- locate the workspace without a caller-supplied identifier. Lookup hint only, never authorization:
-- the tenant row is still matched by source_token_hash. Expand-only: older binaries ignore it.
ALTER TABLE one_time_link_flow
    ADD COLUMN routing_workspace_id INT NULL
        COMMENT 'Tenant routing hint for tenant-plane sources; lookup hint only'
        AFTER source_token_hash;
