package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiWorkspaceGovernance;

/** Tenant-local persistence for workspace AI governance. */
@Mapper
public interface AiWorkspaceGovernanceMapper {
    AiWorkspaceGovernance get(@Param("workspaceId") int workspaceId);

    int upsert(
            @Param("workspaceId") int workspaceId,
            @Param("enabled") boolean enabled,
            @Param("assistantMaxSteps") int assistantMaxSteps);
}
