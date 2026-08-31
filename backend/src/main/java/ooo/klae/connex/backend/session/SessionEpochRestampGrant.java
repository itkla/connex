package ooo.klae.connex.backend.session;

/**
 * Durable authorization for one physical session row to adopt a recovery epoch.
 *
 * @param sessionPrimaryId the recovering ceremony session's stable {@code SPRING_SESSION.PRIMARY_ID}
 * @param epoch the account epoch committed by the recovery transaction
 */
public record SessionEpochRestampGrant(String sessionPrimaryId, int epoch) {
}
