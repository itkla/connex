package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.dto.InviteDto;
import ooo.klae.connex.backend.dto.InvitePreviewDto;

/**
 * Persistence for workspace email-token invites. Control-plane: scoped by
 * explicit workspace id (admin-gated) or by opaque token, never by the active
 * tenant context, so it stays out of the tenant-scope interceptor.
 */
public interface InviteMapper {
    int insert(WorkspaceInvite invite);
    WorkspaceInvite findByToken(String token);
    InvitePreviewDto findPreviewByToken(String token);
    List<InviteDto> findPendingByWorkspace(int workspaceId);
    boolean isRedeemable(String token);
    /** Atomically claims a pending, unexpired invite for its authenticated recipient. */
    int claimAcceptance(
        @Param("id") int id,
        @Param("token") String token,
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    int markRevoked(@Param("id") int id, @Param("workspaceId") int workspaceId);
    int revokePendingForEmail(@Param("workspaceId") int workspaceId, @Param("email") String email);
}
