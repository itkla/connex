package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.PasswordResetToken;

/**
 * Persistence for password reset tokens. Control-plane: keyed by opaque token
 * hash or user id and used by pre-login flows, never by the active tenant
 * context, so it stays out of the tenant-scope interceptor.
 */
public interface PasswordResetTokenMapper {
    int insert(@Param("userId") int userId, @Param("tokenHash") String tokenHash,
            @Param("requestedIp") String requestedIp, @Param("expiryMinutes") int expiryMinutes);

    boolean existsRedeemableByHash(String tokenHash);

    int claimExchange(@Param("tokenHash") String tokenHash,
        @Param("exchangeSessionHash") String exchangeSessionHash);

    boolean isExchangeOwnedBy(@Param("tokenHash") String tokenHash,
        @Param("exchangeSessionHash") String exchangeSessionHash);

    boolean existsExchangedRedeemableByHash(String tokenHash);

    PasswordResetToken findExchangedRedeemableByHash(String tokenHash);

    int countRecentByUser(@Param("userId") int userId, @Param("withinSeconds") int withinSeconds);

    int markConsumed(String tokenHash);

    int invalidateForUser(int userId);

    int deleteExpired();
}
