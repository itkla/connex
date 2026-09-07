package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DeliveryProviderConfig;

/**
 * Data access for per-workspace delivery provider configuration. Every read and write is scoped by an
 * explicit, permission-gated {@code workspaceId} in {@code DeliveryProviderConfigService}, except
 * {@link #findByWebhookTokenHash} which is a global lookup on the unique webhook-token hash: it is how
 * the unauthenticated webhook endpoint resolves a workspace from the opaque token in the URL, never
 * from the request body. SQL lives in {@code resources/mappers/DeliveryProviderConfigMapper.xml}.
 */
public interface DeliveryProviderConfigMapper {

    List<DeliveryProviderConfig> listByWorkspace(@Param("workspaceId") int workspaceId);

    DeliveryProviderConfig findByWorkspaceChannel(
            @Param("workspaceId") int workspaceId, @Param("channel") String channel);

    DeliveryProviderConfig findByWebhookTokenHash(@Param("webhookTokenHash") String webhookTokenHash);

    int upsert(
            @Param("config") DeliveryProviderConfig config,
            @Param("credentialRotated") boolean credentialRotated);

    int delete(@Param("workspaceId") int workspaceId, @Param("channel") String channel);
}
