package ooo.klae.connex.backend.integration;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.SessionSecurityService;

final class PublicApiTestSecuritySupport {
    private PublicApiTestSecuritySupport() {
    }

    static int enrollPasskey(JdbcTemplate jdbcTemplate, User user) {
        String handle = "public-api-" + user.getId() + "-" + UUID.randomUUID();
        byte[] credentialId = handle.getBytes(StandardCharsets.UTF_8);
        jdbcTemplate.update(
            "INSERT INTO webauthn_user_entity (id, user_id, name, display_name) VALUES (?, ?, ?, ?)",
            handle,
            user.getId(),
            user.getUsername(),
            user.getDisplayName());
        jdbcTemplate.update(
            "INSERT INTO webauthn_credential (credential_id, user_entity_user_id, public_key)"
                + " VALUES (?, ?, ?)",
            credentialId,
            handle,
            new byte[] {1});
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT id FROM webauthn_credential WHERE credential_id = ?", Integer.class, credentialId));
    }

    /** Models an approved token owner or administrator whose approval is incidental to the test. */
    static int enrollApprovedPasskey(JdbcTemplate jdbcTemplate, User user) {
        int credential = enrollPasskey(jdbcTemplate, user);
        jdbcTemplate.update("UPDATE webauthn_credential SET privileged_assurance = 'BREAK_GLASS',"
            + " privileged_assured_at = UTC_TIMESTAMP(6) WHERE id = ?", credential);
        return credential;
    }

    /** Binds the step-up to the incidental actor's explicitly approved passkey. */
    static void stepUpApproved(JdbcTemplate jdbcTemplate, MockHttpSession session, int userId) {
        int credential = Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT c.id FROM webauthn_credential c JOIN webauthn_user_entity e"
                + " ON e.id = c.user_entity_user_id WHERE e.user_id = ?"
                + " AND c.privileged_assurance = 'BREAK_GLASS' ORDER BY c.id LIMIT 1", Integer.class, userId));
        stepUp(session, userId);
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR,
            userId + ":" + session.getAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR) + ":" + credential);
    }

    static void stepUp(MockHttpSession session, int userId) {
        session.setAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR,
            System.currentTimeMillis());
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, userId);
    }
}
