package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.IntroPathDto;
import ooo.klae.connex.backend.dto.PersonConnectionDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;

class ConnectionServiceTest extends AbstractServiceTest {

    @Autowired private ConnectionService connectionService;
    @Autowired private IntroductionMapper introductionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PersonEdgeMapper personEdgeMapper;
    @Autowired private ShareMapper shareMapper;

    @Test
    void directConnectionsRedactCompanyAndDisappearAfterPersonRevocationButRemainRemovable() {
        Person source = newPerson(newCompany());
        Workspace sibling = workspaceInOrg(orgId(workspace));
        Company foreignCompany = companyIn(sibling, "Sibling Company");
        Person shared = personIn(sibling, foreignCompany, "Shared Connection");
        assertEquals(1, shareMapper.sharePerson(
            shared.getId(), sibling.getId(), workspace.getId(), currentUser.getId(), false));

        connectionService.addConnection(source.getId(), shared.getId(), "friend", 3, "Trusted");

        PersonConnectionDto connection = connectionService.getConnections(source.getId()).getFirst();
        assertEquals(shared.getId(), connection.getPersonId());
        assertNull(connection.getCompanyId());
        assertNull(connection.getCompanyName());
        connection = connectionService.getTopConnections(source.getId(), 5).getFirst();
        assertEquals(shared.getId(), connection.getPersonId());
        assertNull(connection.getCompanyId());
        assertNull(connection.getCompanyName());

        personMapper.updateProcessingRestrictions(sibling.getId(), shared.getId(), true, true);
        connection = connectionService.getConnections(source.getId()).getFirst();
        assertNotNull(connection.getSuspendedAt());
        assertNotNull(connection.getProvisionCeasedAt());
        assertTrue(connectionService.getTopConnections(source.getId(), 5).isEmpty());

        assertEquals(1, shareMapper.shareCompany(
            foreignCompany.getId(), sibling.getId(), workspace.getId(), currentUser.getId(), false));
        personMapper.updateProcessingRestrictions(sibling.getId(), shared.getId(), false, false);
        connection = connectionService.getConnections(source.getId()).getFirst();
        assertEquals(foreignCompany.getId(), connection.getCompanyId());
        assertEquals(foreignCompany.getName(), connection.getCompanyName());
        connection = connectionService.getTopConnections(source.getId(), 5).getFirst();
        assertEquals(foreignCompany.getId(), connection.getCompanyId());
        assertEquals(foreignCompany.getName(), connection.getCompanyName());

        assertEquals(1, shareMapper.unshareCompany(
            foreignCompany.getId(), sibling.getId(), workspace.getId()));
        connection = connectionService.getConnections(source.getId()).getFirst();
        assertNull(connection.getCompanyId());
        assertNull(connection.getCompanyName());

        assertEquals(1, shareMapper.unsharePerson(shared.getId(), sibling.getId(), workspace.getId()));
        assertTrue(connectionService.getConnections(source.getId()).isEmpty());
        assertTrue(connectionService.getTopConnections(source.getId(), 5).isEmpty());

        connectionService.removeConnection(source.getId(), shared.getId());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person_edge WHERE workspace_id = ? AND source_person_id = ? AND target_person_id = ?",
            Integer.class, workspace.getId(), Math.min(source.getId(), shared.getId()),
            Math.max(source.getId(), shared.getId())));
    }

    @Test
    void introPathStopsTraversingARevokedSharedHub() {
        Person source = engagedPerson("Known Source");
        Person target = personIn(workspace, newCompany(), "Owned Target");
        Workspace sibling = workspaceInOrg(orgId(workspace));
        Person hub = personIn(sibling, null, "Shared Hub");
        assertEquals(1, shareMapper.sharePerson(
            hub.getId(), sibling.getId(), workspace.getId(), currentUser.getId(), false));
        connect(source, hub);
        connect(hub, target);

        IntroPathDto active = connectionService.findIntroPath(target.getId());
        assertTrue(active.isReachable());
        assertFalse(active.isDirectlyKnown());
        assertEquals(
            java.util.List.of(source.getId(), hub.getId(), target.getId()),
            active.getSteps().stream().map(step -> step.getPersonId()).toList());

        assertEquals(1, shareMapper.unsharePerson(hub.getId(), sibling.getId(), workspace.getId()));

        IntroPathDto revoked = connectionService.findIntroPath(target.getId());
        assertFalse(revoked.isReachable());
        assertTrue(revoked.getSteps().isEmpty());
    }

    @Test
    void introPathDoesNotTraverseOrTargetSuspendedContacts() {
        Person source = engagedPerson("Known Source");
        Person bridge = personIn(workspace, newCompany(), "Suspended Bridge");
        Person target = personIn(workspace, newCompany(), "Target");
        connect(source, bridge);
        connect(bridge, target);

        personMapper.updateProcessingRestrictions(workspace.getId(), bridge.getId(), true, false);
        assertFalse(connectionService.findIntroPath(target.getId()).isReachable());

        personMapper.updateProcessingRestrictions(workspace.getId(), bridge.getId(), false, false);
        personMapper.updateProcessingRestrictions(workspace.getId(), target.getId(), true, false);
        assertFalse(connectionService.findIntroPath(target.getId()).isReachable());
    }

    @Test
    void forgedCrossOrgHubCannotAppearAsAConnectionOrIntroPath() {
        Person source = engagedPerson("Known Source");
        Person target = personIn(workspace, newCompany(), "Owned Target");
        Workspace foreign = workspaceInOrg(newOrganization().getId());
        Person forgedHub = personIn(foreign, null, "Forged Hub");
        jdbcTemplate.update(
            "INSERT INTO person_share (person_id, workspace_id, granted_by, can_edit) VALUES (?, ?, ?, ?)",
            forgedHub.getId(), workspace.getId(), currentUser.getId(), false);
        connect(source, forgedHub);
        connect(forgedHub, target);

        assertTrue(connectionService.getConnections(source.getId()).isEmpty());
        assertTrue(connectionService.getTopConnections(source.getId(), 5).isEmpty());
        IntroPathDto path = connectionService.findIntroPath(target.getId());
        assertFalse(path.isReachable());
        assertTrue(path.getSteps().isEmpty());
    }

    @Test
    void topConnectionsRequireVisibleFocalPersonAndRespectShareRevocation() {
        Workspace sibling = workspaceInOrg(orgId(workspace));
        Person focal = personIn(sibling, null, "Sibling Focal");
        Person owned = personIn(workspace, null, "Owned Connection");
        connect(focal, owned);

        assertThrows(ResourceNotFoundException.class,
            () -> connectionService.getTopConnections(focal.getId(), 5));

        assertEquals(1, shareMapper.sharePerson(
            focal.getId(), sibling.getId(), workspace.getId(), currentUser.getId(), false));
        assertEquals(owned.getId(),
            connectionService.getTopConnections(focal.getId(), 5).getFirst().getPersonId());

        assertEquals(1, shareMapper.unsharePerson(focal.getId(), sibling.getId(), workspace.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> connectionService.getTopConnections(focal.getId(), 5));
    }

    @Test
    void topConnectionsCapRequestedLimitsAtFive() {
        Person focal = personIn(workspace, null, "Focal Person");
        for (int index = 0; index < 7; index++) {
            connect(focal, personIn(workspace, null, "Connection " + index));
        }

        assertEquals(5, connectionService.getTopConnections(focal.getId(), 100).size());
        assertTrue(connectionService.getTopConnections(focal.getId(), 0).isEmpty());
    }

    @Test
    void introductionCandidateCompanyFollowsCompanyShareLifecycle() {
        Workspace sibling = workspaceInOrg(orgId(workspace));
        Company foreignCompany = companyIn(sibling, "Shared Employer");
        Person candidate = personIn(workspace, foreignCompany, "Owned Candidate");
        engage(candidate);

        IntroCandidatePerson redacted = candidate(candidate);
        assertNull(redacted.getCompanyId());
        assertNull(redacted.getCompanyName());

        assertEquals(1, shareMapper.shareCompany(
            foreignCompany.getId(), sibling.getId(), workspace.getId(), currentUser.getId(), false));
        IntroCandidatePerson shared = candidate(candidate);
        assertEquals(foreignCompany.getId(), shared.getCompanyId());
        assertEquals(foreignCompany.getName(), shared.getCompanyName());

        assertEquals(1, shareMapper.unshareCompany(
            foreignCompany.getId(), sibling.getId(), workspace.getId()));
        IntroCandidatePerson revoked = candidate(candidate);
        assertNull(revoked.getCompanyId());
        assertNull(revoked.getCompanyName());
    }

    private Person engagedPerson(String name) {
        Person person = personIn(workspace, newCompany(), name);
        engage(person);
        return person;
    }

    private void engage(Person person) {
        Activity activity = new Activity();
        activity.setWorkspaceId(workspace.getId());
        activity.setType("call");
        activity.setSubject("Engagement " + unique());
        activity.setPerson(person);
        activity.setCreatedBy(currentUser);
        activity.setTimestamp("2026-07-01 00:00:00");
        activityMapper.insert(activity);
    }

    private void connect(Person left, Person right) {
        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspace.getId());
        edge.setSourcePersonId(Math.min(left.getId(), right.getId()));
        edge.setTargetPersonId(Math.max(left.getId(), right.getId()));
        edge.setType("knows");
        edge.setStrength(2);
        personEdgeMapper.upsert(edge);
    }

    private IntroCandidatePerson candidate(Person person) {
        return introductionMapper.findCandidatePersons(workspace.getId()).stream()
            .filter(candidate -> candidate.getId() == person.getId())
            .findFirst()
            .orElseThrow();
    }

    private int orgId(Workspace candidate) {
        Integer orgId = workspaceMapper.getOrgId(candidate.getId());
        assertNotNull(orgId);
        return orgId;
    }

    private Organization newOrganization() {
        Organization organization = new Organization();
        organization.setName("Organization " + unique());
        organization.setSlug("org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace workspaceInOrg(int orgId) {
        Workspace candidate = new Workspace();
        candidate.setName("Workspace " + unique());
        candidate.setSlug("workspace-" + unique());
        candidate.setOrgId(orgId);
        workspaceMapper.insert(candidate);
        return candidate;
    }

    private Company companyIn(Workspace owner, String name) {
        Company company = new Company();
        company.setWorkspaceId(owner.getId());
        company.setName(name);
        companyMapper.insert(company);
        return company;
    }

    private Person personIn(Workspace owner, Company company, String name) {
        Person person = new Person();
        person.setWorkspaceId(owner.getId());
        person.setName(name);
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }
}
