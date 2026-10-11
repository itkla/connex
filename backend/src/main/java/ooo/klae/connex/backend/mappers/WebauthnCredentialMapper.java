package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.webauthn.WebauthnCredentialRow;

/**
 * MyBatis mapper for {@code webauthn_credential} (enrolled passkeys).
 * SQL is defined in {@code resources/mappers/WebauthnCredentialMapper.xml}.
 */
public interface WebauthnCredentialMapper {
    /** Returns whether the account currently owns at least one enrolled credential. */
    boolean existsByUserId(int userId);
    WebauthnCredentialRow findByCredentialId(byte[] credentialId);
    List<WebauthnCredentialRow> findByUserEntityUserId(String userEntityUserId);
    List<WebauthnCredentialRow> findByUserEntityUserIdForUpdate(String userEntityUserId);
    int insert(WebauthnCredentialRow row);
    /**
     * Updates assertion state only while the expected counter is current and the new counter
     * advances, or both counters are zero for an authenticator without counter support.
     * @return one if the assertion state was saved, zero if the credential is stale or missing
     */
    int updateMutable(@Param("row") WebauthnCredentialRow row,
            @Param("expectedSignatureCount") long expectedSignatureCount);
    int updateLabel(@Param("credentialId") byte[] credentialId, @Param("label") String label);
    int delete(byte[] credentialId);

    /**
     * Returns the credential's row id when it exists and belongs to the account. Callers hold the
     * account's {@code app_user} row, which every credential delete takes first.
     *
     * @param credentialRowId the {@code webauthn_credential.id} to check
     * @param userId the account it must belong to
     * @return the row id, or null when the credential is gone or another account's
     */
    Integer findOwnedRowId(
            @Param("credentialRowId") int credentialRowId, @Param("userId") int userId);

    /**
     * Copies a source credential's account-wide privileged assurance, if it has any, to a new
     * credential of the same account, stamped with the time it was copied (#1534).
     *
     * @param credentialRowId the new credential
     * @param sourceCredentialRowId the credential whose step-up authorized the enrollment
     * @return one when assurance was copied, zero when the source carries none
     */
    int copyPrivilegedAssurance(
            @Param("credentialRowId") int credentialRowId,
            @Param("sourceCredentialRowId") int sourceCredentialRowId);

    /**
     * Records that the operator recovery session itself enrolled this credential (#1534).
     *
     * @param credentialRowId the replacement credential
     * @return one when the credential exists
     */
    int markBreakGlassAssurance(@Param("credentialRowId") int credentialRowId);
}
