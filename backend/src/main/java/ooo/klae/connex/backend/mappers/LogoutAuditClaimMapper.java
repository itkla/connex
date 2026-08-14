package ooo.klae.connex.backend.mappers;

/** Persists the control-plane idempotency claim for one logical session logout. */
public interface LogoutAuditClaimMapper {

    /**
     * @param sessionHash one-way servlet-session identifier
     * @return one for the first claimant and zero for every duplicate
     */
    int claim(String sessionHash);
}
