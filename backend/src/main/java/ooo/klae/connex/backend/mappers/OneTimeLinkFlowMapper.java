package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

/** Persists session-lineage-bound one-time-link flow grants on the control plane. */
public interface OneTimeLinkFlowMapper {

    /** Clears only an expired claim so its owner can renew the deterministic grant. */
    int clearExpiredClaim(
        @Param("grantHash") String grantHash,
        @Param("exchangeOwnerHash") String exchangeOwnerHash,
        @Param("purpose") String purpose);

    /** Creates or renews the deterministic grant for one browser, source, and purpose. */
    void upsert(
        @Param("grantHash") String grantHash,
        @Param("exchangeOwnerHash") String exchangeOwnerHash,
        @Param("purpose") String purpose,
        @Param("sourceTokenHash") String sourceTokenHash,
        @Param("lifetimeSeconds") long lifetimeSeconds);

    /** Returns the source digest only for an unexpired grant owned by the session lineage. */
    String findValidSourceTokenHash(
        @Param("grantHash") String grantHash,
        @Param("exchangeOwnerHash") String exchangeOwnerHash,
        @Param("purpose") String purpose);

    /** Atomically claims one valid, currently unclaimed grant for a final operation. */
    int claim(
        @Param("grantHash") String grantHash,
        @Param("exchangeOwnerHash") String exchangeOwnerHash,
        @Param("purpose") String purpose,
        @Param("claimHash") String claimHash);

    /** Returns the source digest held by the exact final-operation claim. */
    String findClaimedSourceTokenHash(
        @Param("grantHash") String grantHash,
        @Param("claimHash") String claimHash);

    /** Releases a claim after the tenant domain operation fails. */
    int release(
        @Param("grantHash") String grantHash,
        @Param("claimHash") String claimHash);

    /** Deletes a successfully used grant under its exact claim. */
    int complete(
        @Param("grantHash") String grantHash,
        @Param("claimHash") String claimHash);

    /** Purges expired unclaimed grants and abandoned claims beyond the recovery horizon. */
    int deleteExpired(@Param("abandonedClaimSeconds") long abandonedClaimSeconds);
}
