package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;

/**
 * Org-membership persistence: roles are scoped to their organization, owners are
 * counted for the last-owner guard, and the list queries surface a member's
 * organizations and an org's members.
 */
class OrgMemberMapperTest extends AbstractMapperTest {

    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private OrganizationMapper organizationMapper;

    private int newOrg() {
        Organization org = new Organization();
        org.setName("Org " + unique());
        org.setSlug("org-" + unique());
        organizationMapper.insert(org);
        return org.getId();
    }

    @Test
    void membershipsAreScopedByOrganization() {
        int orgA = newOrg();
        int orgB = newOrg();
        User user = newUser();
        orgMemberMapper.addMember(orgA, user.getId(), "owner");

        assertEquals("owner", orgMemberMapper.getRole(orgA, user.getId()));
        assertTrue(orgMemberMapper.isMember(orgA, user.getId()));
        assertNull(orgMemberMapper.getRole(orgB, user.getId()));
        assertFalse(orgMemberMapper.isMember(orgB, user.getId()));
    }

    @Test
    void addMemberUpsertsRole_andCountsOwners() {
        int orgId = newOrg();
        User user = newUser();
        orgMemberMapper.addMember(orgId, user.getId(), "admin");
        assertEquals(0, orgMemberMapper.countOwners(orgId));

        orgMemberMapper.addMember(orgId, user.getId(), "owner");
        assertEquals("owner", orgMemberMapper.getRole(orgId, user.getId()));
        assertEquals(1, orgMemberMapper.countOwners(orgId));
    }

    @Test
    void getMembers_returnsOrgRosterOwnersFirst() {
        int orgId = newOrg();
        User owner = newUser();
        User admin = newUser();
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");
        orgMemberMapper.addMember(orgId, owner.getId(), "owner");

        var members = orgMemberMapper.getMembers(orgId);
        assertEquals(2, members.size());
        assertEquals("owner", members.getFirst().getOrgRole());
    }

    @Test
    void getMembershipsForUser_listsAdministeredOrgs() {
        int orgId = newOrg();
        User user = newUser();
        orgMemberMapper.addMember(orgId, user.getId(), "admin");

        var memberships = orgMemberMapper.getMembershipsForUser(user.getId());
        assertEquals(1, memberships.size());
        assertEquals(orgId, memberships.getFirst().getId());
        assertEquals("admin", memberships.getFirst().getOrgRole());
    }
}
