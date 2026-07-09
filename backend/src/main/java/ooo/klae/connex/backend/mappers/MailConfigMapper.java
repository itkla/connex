package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkspaceMailConfig;

/**
 * Data access for per-workspace SMTP config. Control-plane: every statement is
 * scoped by an explicit, permission-gated {@code workspaceId} in
 * {@code WorkspaceMailConfigService}, so it is intentionally NOT in
 * {@code TenantScopeInterceptor.SCOPED_NAMESPACES} (mirrors AllowedDomainMapper).
 * SQL lives in {@code resources/mappers/MailConfigMapper.xml}.
 */
public interface MailConfigMapper {

    WorkspaceMailConfig findByWorkspace(int workspaceId);

    List<WorkspaceMailConfig> listLegacySecretConfigs();

    int upsert(WorkspaceMailConfig config);

    int updatePasswordReference(@Param("workspaceId") int workspaceId, @Param("passwordEnc") String passwordEnc);

    int delete(int workspaceId);
}
