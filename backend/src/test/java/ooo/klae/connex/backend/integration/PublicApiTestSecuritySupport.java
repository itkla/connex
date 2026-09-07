package ooo.klae.connex.backend.integration;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.SessionSecurityService;

final class PublicApiTestSecuritySupport {
    private PublicApiTestSecuritySupport() {
    }

    static void enrollPasskey(JdbcTemplate jdbcTemplate, User user) {
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
    }

    static void stepUp(MockHttpSession session, int userId) {
        session.setAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR,
            System.currentTimeMillis());
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, userId);
    }
}
