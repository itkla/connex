package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.EmailChangeToken;

/**
 * Persistence for verified email-change tokens. Control-plane: keyed by opaque
 * token hash or user id and used outside the active tenant context, so it stays
 * out of the tenant-scope interceptor.
 */
public interface EmailChangeTokenMapper {
    int insert(@Param("userId") int userId, @Param("newEmail") String newEmail,
            @Param("tokenHash") String tokenHash, @Param("requestedIp") String requestedIp,
            @Param("expiryMinutes") int expiryMinutes);

    boolean existsRedeemableByHash(String tokenHash);

    int claimExchange(@Param("tokenHash") String tokenHash,
        @Param("exchangeOwnerHash") String exchangeOwnerHash);

    boolean isExchangeOwnedBy(@Param("tokenHash") String tokenHash,
        @Param("exchangeOwnerHash") String exchangeOwnerHash);

    boolean existsExchangedRedeemableByHash(String tokenHash);

    EmailChangeToken findExchangedRedeemableByHash(String tokenHash);

    int countRecentByUser(@Param("userId") int userId, @Param("withinSeconds") int withinSeconds);

    int markConsumed(String tokenHash);

    int invalidateForUser(int userId);

    int deleteExpired();
}
