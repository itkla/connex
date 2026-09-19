package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

/**
 * Persists the control-plane single-use ledger for operator break-glass recovery tokens (#1532).
 * SQL is defined in {@code resources/mappers/PrivilegedMfaRecoveryRedemptionMapper.xml}.
 */
public interface PrivilegedMfaRecoveryRedemptionMapper {

    /**
     * Spends a recovery token. Callers hold the redeeming account's {@code app_user} row
     * exclusively and run inside the recovery transaction, so a later failure rolls the row back
     * and leaves the token unspent.
     *
     * @param tokenDigest hex SHA-256 of the configured token digest
     * @param userId the redeeming account
     * @param operator the configured actor who issued the token
     * @return one for the first redemption and zero when the token was already spent
     */
    int insertIfAbsent(
            @Param("tokenDigest") String tokenDigest,
            @Param("userId") int userId,
            @Param("operator") String operator);
}
