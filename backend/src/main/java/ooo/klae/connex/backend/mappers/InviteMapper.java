package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.dto.InviteDto;
import ooo.klae.connex.backend.dto.InvitePreviewDto;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Persistence for workspace email-token invites. Control-plane: scoped by
 * explicit workspace id (admin-gated) or by opaque token, never by the active
 * tenant context, so it stays out of the tenant-scope interceptor.
 */
public interface InviteMapper {
    default int insert(WorkspaceInvite invite) {
        invite.setTokenHash(digest(invite.getToken()));
        return insertHashed(invite);
    }
    int insertHashed(WorkspaceInvite invite);
    default WorkspaceInvite findByToken(String token) {
        return findByTokenHash(digest(token));
    }
    WorkspaceInvite findByTokenHash(String tokenHash);
    default InvitePreviewDto findPreviewByToken(String token) {
        return findPreviewByTokenHashUnclaimed(digest(token));
    }
    InvitePreviewDto findPreviewByTokenHashUnclaimed(String tokenHash);
    InvitePreviewDto findPreviewByTokenHash(String tokenHash);
    List<InviteDto> findPendingByWorkspace(int workspaceId);
    default int claimExchange(String token, String exchangeOwnerHash) {
        return claimExchangeByHash(digest(token), exchangeOwnerHash);
    }
    int claimExchangeByHash(@Param("tokenHash") String tokenHash,
        @Param("exchangeOwnerHash") String exchangeOwnerHash);
    boolean isExchangeOwnedByHash(@Param("tokenHash") String tokenHash,
        @Param("exchangeOwnerHash") String exchangeOwnerHash);
    default boolean isRedeemable(String token) {
        return isRedeemableByHash(digest(token));
    }
    boolean isRedeemableByHash(String tokenHash);
    boolean isExchangedRedeemable(String tokenHash);
    /** Atomically claims a pending, unexpired invite for its authenticated recipient. */
    default int claimAcceptance(int id, String token, int workspaceId, int userId) {
        return claimAcceptanceByUnclaimedHash(id, digest(token), workspaceId, userId);
    }
    int claimAcceptanceByUnclaimedHash(
        @Param("id") int id,
        @Param("tokenHash") String tokenHash,
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    /** Atomically claims an exchanged invite without reintroducing its raw bearer. */
    int claimAcceptanceByHash(
        @Param("id") int id,
        @Param("tokenHash") String tokenHash,
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    int markRevoked(@Param("id") int id, @Param("workspaceId") int workspaceId);
    int revokePendingForEmail(@Param("workspaceId") int workspaceId, @Param("email") String email);

    private static String digest(String token) {
        return token == null ? null : OneTimeTokenDigest.sha256(token);
    }
}
