package ooo.klae.connex.backend.session;

/**
 * Durable authorization for one logical servlet session to adopt a recovery epoch.
 *
 * @param sessionId the recovering ceremony's logical {@code HttpSession.getId()}
 * @param epoch the account epoch committed by the recovery transaction
 */
public record SessionEpochRestampGrant(String sessionId, int epoch) {
}
