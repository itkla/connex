package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;

/**
 * The org-level email-domain allowlist (#316, Option B): org admin/owner-gated CRUD, and
 * {@code isJoinAllowed} returning true for an empty list (unrestricted) else matching the domain
 * case-insensitively.
 */
class OrgAllowedDomainServiceTest extends AbstractServiceTest {

    @Autowired private OrgAllowedDomainService orgAllowedDomainService;
    @Autowired private OrgMemberMapper orgMemberMapper;

    private int orgId;

    @BeforeEach
    void enrollOwner() {
        orgId = workspaceMapper.getOrgId(workspace.getId());
        orgMemberMapper.addMember(orgId, currentUser.getId(), "owner");
    }

    @Test
    void emptyList_isUnrestricted() {
        assertTrue(orgAllowedDomainService.isJoinAllowed(orgId, "anyone@anywhere.com"));
        assertFalse(orgAllowedDomainService.hasRestrictions(orgId));
    }

    @Test
    void restrictsToAllowlist_caseInsensitively() {
        orgAllowedDomainService.addDomain(orgId, currentUser.getId(), "Acme.com");

        assertTrue(orgAllowedDomainService.hasRestrictions(orgId));
        assertTrue(orgAllowedDomainService.isJoinAllowed(orgId, "Alice@ACME.com"));
        assertFalse(orgAllowedDomainService.isJoinAllowed(orgId, "bob@other.com"));
        assertEquals(List.of("acme.com"), orgAllowedDomainService.listDomains(orgId, currentUser.getId()));
    }

    @Test
    void removeDomain_reopensTheOrg() {
        orgAllowedDomainService.addDomain(orgId, currentUser.getId(), "acme.com");
        orgAllowedDomainService.removeDomain(orgId, currentUser.getId(), "acme.com");

        assertFalse(orgAllowedDomainService.hasRestrictions(orgId));
        assertTrue(orgAllowedDomainService.isJoinAllowed(orgId, "bob@other.com"));
    }

    @Test
    void addDomain_rejectsGarbage() {
        assertThrows(BadRequestException.class,
            () -> orgAllowedDomainService.addDomain(orgId, currentUser.getId(), "not a domain"));
        assertThrows(BadRequestException.class,
            () -> orgAllowedDomainService.addDomain(orgId, currentUser.getId(), "  "));
    }

    @Test
    void crud_requiresOrgAdmin() {
        User outsider = newUser();
        assertThrows(ForbiddenException.class,
            () -> orgAllowedDomainService.listDomains(orgId, outsider.getId()));
        assertThrows(ForbiddenException.class,
            () -> orgAllowedDomainService.addDomain(orgId, outsider.getId(), "acme.com"));
        assertThrows(ForbiddenException.class,
            () -> orgAllowedDomainService.removeDomain(orgId, outsider.getId(), "acme.com"));
    }
}
