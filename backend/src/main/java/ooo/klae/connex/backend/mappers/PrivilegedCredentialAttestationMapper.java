package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

/**
 * Records the per-organization privileged MFA coverage of passkeys (#1534). Every insert leaves
 * an existing row untouched, so a direct source written first is never replaced by an inherited
 * one. SQL is defined in {@code resources/mappers/PrivilegedCredentialAttestationMapper.xml}.
 */
public interface PrivilegedCredentialAttestationMapper {

    /**
     * Covers a newly registered passkey in every organization its account founded and still owns.
     * Callers hold the account's {@code app_user} row exclusively, which founding also locks.
     *
     * @param credentialRowId the new passkey's {@code webauthn_credential.id}
     * @param userId the passkey's account
     * @return the number of organizations considered
     */
    int insertFounderCoverage(
            @Param("credentialRowId") int credentialRowId,
            @Param("userId") int userId);

    /**
     * Copies a source passkey's coverage to a new passkey of the same account, as inherited rows.
     * Callers hold the account's {@code app_user} row exclusively and have checked that the source
     * belongs to that account.
     *
     * @param credentialRowId the new passkey's {@code webauthn_credential.id}
     * @param sourceCredentialRowId the passkey whose step-up authorized the enrollment
     * @return the number of organizations considered
     */
    int insertInheritedCoverage(
            @Param("credentialRowId") int credentialRowId,
            @Param("sourceCredentialRowId") int sourceCredentialRowId);

    /**
     * Covers every existing passkey of an organization's founding owner in that organization.
     * Callers hold the owner's {@code app_user} row, which registration locks exclusively.
     *
     * @param orgId the organization just founded
     * @param userId the founding owner
     * @return the number of passkeys considered
     */
    int insertFoundingCoverage(
            @Param("orgId") int orgId,
            @Param("userId") int userId);
}
