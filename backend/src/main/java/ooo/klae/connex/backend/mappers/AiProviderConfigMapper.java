package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiProviderConfig;

/**
 * Data access for per-organization BYOP AI provider configuration. Control-plane:
 * callers pass an explicit, org-admin-gated {@code orgId}.
 */
public interface AiProviderConfigMapper {
    AiProviderConfig findByOrg(@Param("orgId") int orgId);
    void upsert(AiProviderConfig config);
    void deleteByOrg(@Param("orgId") int orgId);
}
