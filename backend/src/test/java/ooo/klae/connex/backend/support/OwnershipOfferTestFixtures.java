package ooo.klae.connex.backend.support;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.OwnershipOfferService;
import ooo.klae.connex.backend.services.SessionSecurityService;

/** Direct credential fixtures; ceremonies and enrollment authorization are covered by their owning tests. */
public final class OwnershipOfferTestFixtures {
    private OwnershipOfferTestFixtures() {}

    /** Starts an isolated scheduled-worker fixture with the same disposable state as a restart. */
    public static void restartExpiryTraversal(OwnershipOfferService offers) {
        ReflectionTestUtils.setField(offers, "expiryTraversal", null);
    }

    /** Inserts one credential belonging to the given account. */
    public static int passkey(JdbcTemplate jdbc, User user) {
        List<String> handles = jdbc.queryForList("SELECT id FROM webauthn_user_entity WHERE user_id = ?", String.class, user.getId());
        String handle = handles.isEmpty() ? "ownership-" + UUID.randomUUID() : handles.getFirst();
        if (handles.isEmpty()) {
            jdbc.update("INSERT INTO webauthn_user_entity (id, user_id, name, display_name) VALUES (?, ?, ?, ?)",
                handle, user.getId(), user.getUsername(), user.getDisplayName());
        }
        byte[] credential = ("ownership-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO webauthn_credential (credential_id, user_entity_user_id, public_key) VALUES (?, ?, ?)",
            credential, handle, new byte[]{1});
        return Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM webauthn_credential WHERE credential_id = ?", Integer.class, credential));
    }

    /**
     * Models an approved administrator wherever the test makes them privileged; approval is
     * incidental to the test. Coverage subjects must use {@link #passkey} and organization rows.
     */
    public static int approvedPasskey(JdbcTemplate jdbc, User user) {
        List<Integer> approved = jdbc.queryForList("SELECT c.id FROM webauthn_credential c"
            + " JOIN webauthn_user_entity e ON e.id = c.user_entity_user_id WHERE e.user_id = ?"
            + " AND c.privileged_assurance = 'BREAK_GLASS' ORDER BY c.id", Integer.class, user.getId());
        if (!approved.isEmpty()) {
            return approved.getFirst();
        }
        int credential = passkey(jdbc, user);
        jdbc.update("UPDATE webauthn_credential SET privileged_assurance = 'BREAK_GLASS',"
            + " privileged_assured_at = UTC_TIMESTAMP(6) WHERE id = ?", credential);
        return credential;
    }

    /** Stamps an actual owned credential and current epoch into a test session. */
    public static void stepUp(JdbcTemplate jdbc, User user, HttpSession session, int credentialId) {
        long now = System.currentTimeMillis();
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR, user.getId() + ":" + now + ":" + credentialId);
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR,
            jdbc.queryForObject("SELECT session_epoch FROM app_user WHERE id = ?", Integer.class, user.getId()));
    }

    /** Enrolls and stamps an incidental approved administrator for the current service request. */
    public static int stepUpApprovedCurrent(JdbcTemplate jdbc, User user) {
        var attributes = RequestContextHolder.currentRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            throw new IllegalStateException("Ownership test needs an HTTP request");
        }
        int credential = approvedPasskey(jdbc, user);
        stepUp(jdbc, user, servlet.getRequest().getSession(), credential);
        return credential;
    }

    /** Enrolls and stamps the account used by a service test's current request. */
    public static int stepUpCurrent(JdbcTemplate jdbc, User user) {
        var attributes = RequestContextHolder.currentRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            throw new IllegalStateException("Ownership test needs an HTTP request");
        }
        int credential = passkey(jdbc, user);
        stepUp(jdbc, user, servlet.getRequest().getSession(), credential);
        return credential;
    }
}
