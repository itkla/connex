package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.PasskeyBootstrapConfirmationToken;

/**
 * Persistence for first-passkey enrollment confirmations. Control-plane: keyed by opaque token
 * digest or user id and used by account bootstrap, never by the active tenant context, so it
 * stays out of the tenant-scope interceptor.
 */
public interface PasskeyBootstrapConfirmationTokenMapper {

    int insert(@Param("userId") int userId, @Param("tokenHash") String tokenHash,
            @Param("sessionPrimaryId") String sessionPrimaryId,
            @Param("requestedIp") String requestedIp, @Param("expiryMinutes") int expiryMinutes);

    PasskeyBootstrapConfirmationToken findRedeemableByHash(String tokenHash);

    int countRecentByUser(@Param("userId") int userId, @Param("withinSeconds") int withinSeconds);

    int markConsumed(@Param("tokenHash") String tokenHash, @Param("userId") int userId,
            @Param("sessionPrimaryId") String sessionPrimaryId);

    int invalidateForUser(int userId);

    int deleteExpired();
}
