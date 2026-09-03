package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ConnectorConfig;

/**
 * Data access for per-workspace third-party connector configuration. Every read and write is scoped by
 * an explicit, permission-gated {@code workspaceId} in {@code ConnectorConfigService}. SQL lives in
 * {@code resources/mappers/ConnectorConfigMapper.xml}.
 */
public interface ConnectorConfigMapper {

    List<ConnectorConfig> listByWorkspace(@Param("workspaceId") int workspaceId);

    ConnectorConfig findByWorkspaceConnector(
            @Param("workspaceId") int workspaceId, @Param("connector") String connector);

    Integer findCurrentAudienceTargetIdForShare(
            @Param("workspaceId") int workspaceId,
            @Param("connector") String connector,
            @Param("configId") int configId,
            @Param("configVersion") long configVersion);

    int upsert(ConnectorConfig config);

    int delete(@Param("workspaceId") int workspaceId, @Param("connector") String connector);
}
