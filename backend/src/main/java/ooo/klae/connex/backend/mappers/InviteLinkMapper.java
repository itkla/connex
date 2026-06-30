package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkspaceInviteLink;
import ooo.klae.connex.backend.dto.InviteLinkDto;
import ooo.klae.connex.backend.dto.InviteLinkPreviewDto;

/**
 * Data access for shareable workspace invite links. This is a control-plane mapper, scoped by an
 * explicit {@code workspaceId} (permission-gated in {@code InviteLinkService}) or by the opaque
 * token — it is intentionally NOT registered in {@code TenantScopeInterceptor.SCOPED_NAMESPACES},
 * mirroring {@code InviteMapper}. SQL lives in {@code resources/mappers/InviteLinkMapper.xml}.
 */
public interface InviteLinkMapper {
    int insert(@Param("workspaceId") int workspaceId, @Param("token") String token,
            @Param("role") String role, @Param("expiresInDays") Integer expiresInDays,
            @Param("maxUses") Integer maxUses, @Param("createdById") int createdById);

    WorkspaceInviteLink findByToken(String token);

    InviteLinkPreviewDto findPreviewByToken(String token);

    List<InviteLinkDto> findActiveByWorkspace(int workspaceId);

    int markRevoked(@Param("id") int id, @Param("workspaceId") int workspaceId);

    /** Atomically claims one use; returns rows affected (0 when revoked, expired, or exhausted). */
    int incrementUsedCount(int id);

    int recordRedemption(@Param("linkId") int linkId, @Param("userId") int userId);

    boolean hasRedeemed(@Param("linkId") int linkId, @Param("userId") int userId);
}
