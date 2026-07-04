package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

/**
 * The org audit trail (#316): an org-level action is attributed to its organization ({@code org_id})
 * and surfaced by the org-scoped read, and one org's events are not visible from another.
 */
class OrgAuditViewTest extends AbstractServiceTest {

    @Autowired private AuditService auditService;
    @Autowired private OrgAllowedDomainService orgAllowedDomainService;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private OrganizationMapper organizationMapper;

    @Test
    void orgActionIsAttributedToItsOrgAndVisibleInTheTrail() {
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        orgMemberMapper.addMember(orgId, currentUser.getId(), "owner");

        orgAllowedDomainService.addDomain(orgId, currentUser.getId(), "acme.com");

        List<AuditLog> events = auditService.recentForOrg(orgId, 50, 0);
        assertTrue(events.stream().anyMatch(e -> "org.allowed_domain.add".equals(e.getAction())
                && Integer.valueOf(orgId).equals(e.getOrgId()) && e.getWorkspaceId() == null),
            "an org-level action must be attributed to its org (and no workspace) and appear in the org audit trail");
    }

    @Test
    void anotherOrgsAuditTrailIsIsolated() {
        int orgA = workspaceMapper.getOrgId(workspace.getId());
        orgMemberMapper.addMember(orgA, currentUser.getId(), "owner");
        orgAllowedDomainService.addDomain(orgA, currentUser.getId(), "acme.com");

        Organization orgB = new Organization();
        orgB.setName("Org B " + unique());
        orgB.setSlug("org-b-" + unique());
        organizationMapper.insert(orgB);

        assertTrue(auditService.recentForOrg(orgB.getId(), 50, 0).isEmpty(),
            "org B must not see org A's audit events");
    }
}
