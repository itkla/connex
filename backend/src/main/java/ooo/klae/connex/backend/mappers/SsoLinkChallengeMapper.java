package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.SsoLinkChallenge;

/**
 * Persistence for SSO account-linking challenges. Control-plane: keyed by opaque
 * token hash or user id and used by the pre-login linking flow, never by the active
 * tenant context, so it is intentionally NOT in
 * {@code TenantScopeInterceptor.SCOPED_NAMESPACES} (mirrors PasswordResetTokenMapper).
 * SQL lives in {@code resources/mappers/SsoLinkChallengeMapper.xml}.
 */
public interface SsoLinkChallengeMapper {

    int insert(@Param("challenge") SsoLinkChallenge challenge, @Param("expiryMinutes") int expiryMinutes);

    SsoLinkChallenge findByTokenHash(String tokenHash);

    SsoLinkChallenge lockByTokenHash(String tokenHash);

    int markConsumed(int id);

    int invalidateForUser(int userId);
}
