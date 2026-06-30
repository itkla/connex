package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/**
 * The per-workspace domain allowlist: CRUD is WORKSPACE_SETTINGS-gated, and {@code isJoinAllowed}
 * treats an empty allowlist as unrestricted (the non-breaking default) and otherwise matches the
 * email's domain (#81 Phase 4).
 */
class AllowedDomainServiceTest extends AbstractServiceTest {

    @Autowired private AllowedDomainService allowedDomainService;

    @Test
    void isJoinAllowed_emptyAllowlist_allowsAnyone() {
        assertTrue(allowedDomainService.isJoinAllowed(workspace.getId(), "anyone@anywhere.com"));
    }

    @Test
    void isJoinAllowed_matchesDomainCaseInsensitively() {
        allowedDomainService.addDomain(workspace.getId(), currentUser.getId(), "Acme.COM");

        assertTrue(allowedDomainService.isJoinAllowed(workspace.getId(), "Jo@acme.com"));
        assertFalse(allowedDomainService.isJoinAllowed(workspace.getId(), "jo@other.com"));
    }

    @Test
    void addAndRemoveDomain_roundTrips() {
        allowedDomainService.addDomain(workspace.getId(), currentUser.getId(), "acme.com");
        List<String> after = allowedDomainService.addDomain(workspace.getId(), currentUser.getId(), "beta.io");
        assertTrue(after.contains("acme.com"));
        assertTrue(after.contains("beta.io"));

        allowedDomainService.removeDomain(workspace.getId(), currentUser.getId(), "acme.com");
        assertFalse(allowedDomainService.listDomains(workspace.getId(), currentUser.getId()).contains("acme.com"));
    }

    @Test
    void addDomain_normalizesAndRejectsGarbage() {
        allowedDomainService.addDomain(workspace.getId(), currentUser.getId(), "  @Example.COM ");
        assertTrue(allowedDomainService.listDomains(workspace.getId(), currentUser.getId()).contains("example.com"));

        assertThrows(BadRequestException.class,
            () -> allowedDomainService.addDomain(workspace.getId(), currentUser.getId(), "notadomain"));
    }

    @Test
    void mutations_requireWorkspaceSettingsPermission() {
        User member = newUser();
        assertThrows(ForbiddenException.class,
            () -> allowedDomainService.addDomain(workspace.getId(), member.getId(), "acme.com"));
        assertThrows(ForbiddenException.class,
            () -> allowedDomainService.listDomains(workspace.getId(), member.getId()));
    }
}
