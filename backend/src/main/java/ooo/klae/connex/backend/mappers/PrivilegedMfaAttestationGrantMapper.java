package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.PrivilegedMfaAttestationGrant;

/**
 * Persists grantor-issued privileged MFA attestation codes (#1534). Every writer runs under the
 * grantee's {@code app_user} root. SQL is defined in
 * {@code resources/mappers/PrivilegedMfaAttestationGrantMapper.xml}.
 */
public interface PrivilegedMfaAttestationGrantMapper {

    /**
     * Issues a grant that expires 24 hours after issuance, by the database clock.
     *
     * @param grant the grant, whose generated id is set on return
     * @return the number of rows inserted
     */
    int insert(PrivilegedMfaAttestationGrant grant);

    /**
     * Reads one grant.
     *
     * @param id the grant id
     * @return the grant, or null when it no longer exists
     */
    PrivilegedMfaAttestationGrant findById(@Param("id") long id);

    /**
     * Finds the grant a code digest names, without taking locks. The redemption locks the roots the
     * grant names before it claims it.
     *
     * @param codeDigest the grantee-bound code digest
     * @return the grant, or null when no grant carries the digest
     */
    PrivilegedMfaAttestationGrant findByDigest(@Param("codeDigest") String codeDigest);

    /**
     * Revokes the grantee's grant for an organization that is neither redeemed nor revoked.
     *
     * @param granteeUserId the grantee
     * @param orgId the organization
     * @param revokedByUserId the account revoking or superseding it
     * @return the number of grants revoked
     */
    int revokeOpen(
            @Param("granteeUserId") int granteeUserId,
            @Param("orgId") int orgId,
            @Param("revokedByUserId") int revokedByUserId);

    /**
     * Claims a grant for the grantee's passkey while it is unredeemed, unrevoked, unexpired and
     * under the failure limit.
     *
     * @param id the grant id
     * @param granteeUserId the grantee redeeming it
     * @param credentialRowId the passkey the redemption attests
     * @return one when the claim succeeded, zero otherwise
     */
    int claim(
            @Param("id") long id,
            @Param("granteeUserId") int granteeUserId,
            @Param("credentialRowId") int credentialRowId);

    /**
     * Counts one refused redemption against every grant of the grantee that is still redeemable.
     * A grant that reaches five refused redemptions can no longer be claimed.
     *
     * @param granteeUserId the grantee whose redemption was refused
     * @return the number of grants counted against
     */
    int burnOpen(@Param("granteeUserId") int granteeUserId);
}
