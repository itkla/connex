package ooo.klae.connex.backend.services;

import static ooo.klae.connex.backend.support.OrganizationShareScopes.orgWorkspaceIdsJson;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ShareBlockedPrivacyHoldException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

class ShareServiceTest extends AbstractServiceTest {

    @Autowired ShareService shareService;
    @Autowired NoteService noteService;
    @Autowired WorkspaceService workspaceService;
    @Autowired TenantContext tenantContext;
    @Autowired OrganizationMapper organizationMapper;
    @Autowired ShareMapper shareMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContext() {
        clearRequestContext();
    }

    private Company companyIn(int workspaceId) {
        Company company = new Company();
        company.setName("Acme " + unique());
        company.setWorkspaceId(workspaceId);
        companyMapper.insert(company);
        return company;
    }

    /**
     * Creates a second workspace inside {@code first}'s organization the way a real
     * owner does: from an administrative tenant context (the placement rule only
     * reuses the active org for owner/admin creators).
     */
    private WorkspaceMembershipDto createSiblingWorkspace(WorkspaceMembershipDto first, String name) {
        tenantContext.set(first.getId(), workspaceService.getOrgId(first.getId()), currentUser.getId(), "owner", null);
        WorkspaceMembershipDto sibling = workspaceService.createWorkspace(name, currentUser.getId());
        authenticateAs(currentUser, first.getId());
        return sibling;
    }

    @Test
    void sharingMakesACompanyVisibleToTheGrantee() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Owner WS", currentUser.getId());
        WorkspaceMembershipDto b = createSiblingWorkspace(a, "Grantee WS");
        Company company = companyIn(a.getId());

        assertNull(companyMapper.getCompanyById(b.getId(), company.getId()));
        assertFalse(companyMapper.exists(b.getId(), company.getId()));

        authenticateAs(currentUser, a.getId());
        shareService.share("company", company.getId(), b.getId(), false);
        authenticateAs(currentUser, b.getId());

        Company seenByB = companyMapper.getCompanyById(b.getId(), company.getId());
        assertNotNull(seenByB);
        assertTrue(companyMapper.exists(b.getId(), company.getId()));
        assertEquals(a.getId(), seenByB.getWorkspaceId());
    }

    @Test
    void unshareRemovesVisibility() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Owner2 WS", currentUser.getId());
        WorkspaceMembershipDto b = createSiblingWorkspace(a, "Grantee2 WS");
        Company company = companyIn(a.getId());

        authenticateAs(currentUser, a.getId());
        shareService.share("company", company.getId(), b.getId(), false);
        assertNotNull(companyMapper.getCompanyById(b.getId(), company.getId()));

        shareService.unshare("company", company.getId(), b.getId());
        authenticateAs(currentUser, b.getId());

        assertNull(companyMapper.getCompanyById(b.getId(), company.getId()));
    }

    /**
     * Reader-time authorization masks a frozen reference label immediately after unshare and restores
     * the original link after re-share without rewriting the note or its reference row.
     */
    @Test
    void unshareMasksExistingNoteReferencesAndBacklinksUntilReshared() {
        WorkspaceMembershipDto owner = workspaceService.createWorkspace("Reference Owner WS", currentUser.getId());
        WorkspaceMembershipDto grantee = createSiblingWorkspace(owner, "Reference Grantee WS");
        Company company = companyIn(owner.getId());

        authenticateAs(currentUser, owner.getId());
        shareService.share("company", company.getId(), grantee.getId(), false);
        authenticateAs(currentUser, grantee.getId());
        Note draft = new Note();
        draft.setVisibility("workspace");
        draft.setContent("See [Confidential account](company:" + company.getId() + ")");
        Note source = noteService.create(draft);
        assertTrue(noteService.getNoteById(source.getId()).getContent().contains("Confidential account"));
        assertEquals(1, noteService.getNotesReferencing("company", company.getId()).size());

        authenticateAs(currentUser, owner.getId());
        shareService.unshare("company", company.getId(), grantee.getId());
        authenticateAs(currentUser, grantee.getId());
        Note hidden = noteService.getNoteById(source.getId());
        assertEquals("See (unavailable reference)", hidden.getContent());
        assertTrue(hidden.getReferences().isEmpty());
        assertThrows(ResourceNotFoundException.class,
            () -> noteService.getNotesReferencing("company", company.getId()));

        authenticateAs(currentUser, owner.getId());
        shareService.share("company", company.getId(), grantee.getId(), false);
        authenticateAs(currentUser, grantee.getId());
        Note restored = noteService.getNoteById(source.getId());
        assertTrue(restored.getContent().contains("Confidential account"));
        assertTrue(restored.getReferences().stream()
            .anyMatch(reference -> "company".equals(reference.getRefType())
                && reference.getRefId() == company.getId()));
        assertEquals(List.of(source.getId()), noteService
            .getNotesReferencing("company", company.getId()).stream().map(Note::getId).toList());
    }

    @Test
    void cannotShareToAWorkspaceYouDoNotBelongTo() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Owner3 WS", currentUser.getId());
        Company company = companyIn(a.getId());

        Workspace foreign = new Workspace();
        foreign.setName("Foreign WS");
        foreign.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreign);
        User outsider = newUser();
        workspaceMapper.addMember(foreign.getId(), outsider.getId(), "owner");

        tenantContext.set(a.getId(), workspaceService.getOrgId(a.getId()), currentUser.getId(), "owner", null);
        assertThrows(ForbiddenException.class,
            () -> shareService.share("company", company.getId(), foreign.getId(), false));
    }

    @Test
    void cannotShareAcrossOrganizations() {
        WorkspaceMembershipDto a = workspaceService.createWorkspace("Org1 WS", currentUser.getId());
        Company company = companyIn(a.getId());

        Organization otherOrg = new Organization();
        otherOrg.setName("Other Org");
        otherOrg.setSlug("other-org-" + unique());
        organizationMapper.insert(otherOrg);
        Workspace otherOrgWs = new Workspace();
        otherOrgWs.setOrgId(otherOrg.getId());
        otherOrgWs.setName("Other Org WS");
        otherOrgWs.setSlug("other-org-ws-" + unique());
        workspaceMapper.insert(otherOrgWs);
        workspaceMapper.addMember(otherOrgWs.getId(), currentUser.getId(), "owner");

        tenantContext.set(a.getId(), workspaceService.getOrgId(a.getId()), currentUser.getId(), "owner", null);
        assertThrows(ForbiddenException.class,
            () -> shareService.share("company", company.getId(), otherOrgWs.getId(), false));
    }

    /**
     * Workspace names and their ordering are control-plane data the tenant listing no longer
     * carries, so the service hydrates both from its organization snapshot. The siblings are
     * created in reverse alphabetical order, so an id-ordered listing would fail this. The
     * assertion on the raw tenant rows is what makes this a regression test rather than a
     * behaviour-preservation one: the name-ordered, named response is identical to what the
     * removed {@code JOIN workspace} returned, so only the absence of a workspace name on the
     * tenant side distinguishes the two implementations.
     */
    @Test
    void listSharesHydratesWorkspaceNamesFromTheControlSnapshotAndOrdersThemByName() {
        WorkspaceMembershipDto owner = workspaceService.createWorkspace("Hydration Owner", currentUser.getId());
        WorkspaceMembershipDto zulu = createSiblingWorkspace(owner, "Zulu Grantee");
        WorkspaceMembershipDto alpha = createSiblingWorkspace(owner, "Alpha Grantee");
        Company company = companyIn(owner.getId());

        authenticateAs(currentUser, owner.getId());
        shareService.share("company", company.getId(), zulu.getId(), false);
        shareService.share("company", company.getId(), alpha.getId(), true);

        assertTrue(shareMapper.listCompanyShares(owner.getId(), company.getId()).stream()
                .allMatch(row -> row.getWorkspaceName() == null),
            "the tenant listing must carry no workspace name; the name is control data the "
                + "service hydrates, so a listing statement that names workspaces itself cannot "
                + "run in a dedicated organization catalog. This has to be read before the "
                + "service call: hydration mutates the rows MyBatis caches for this transaction");

        List<ShareDto> shares = shareService.listShares("company", company.getId());

        assertTrue(zulu.getId() < alpha.getId(),
            "the fixture must create the alphabetically-first sibling last for this to bite");
        assertEquals(List.of(alpha.getId(), zulu.getId()),
            shares.stream().map(ShareDto::getWorkspaceId).toList());
        assertEquals(List.of("Alpha Grantee", "Zulu Grantee"),
            shares.stream().map(ShareDto::getWorkspaceName).toList());
        assertTrue(shares.getFirst().isCanEdit());
        assertNotNull(shares.getFirst().getCreatedAt());
    }

    /**
     * A share row whose target workspace is absent from the control snapshot is omitted, not a
     * hard failure. The row this test plants targets a workspace in ANOTHER organization, which
     * is the case that narrows: the removed {@code JOIN workspace} carried no organization
     * predicate, so it listed such a row under the foreign workspace's name, and only the grant
     * statements ever refused a cross-organization target. The row is now omitted, and therefore
     * no longer revocable through the UI. The other omitted case — a target workspace row that no
     * longer exists at all, possible since V65 dropped the foreign key — behaves exactly as the
     * old join did.
     */
    @Test
    void listSharesOmitsCrossOrganizationTargetsTheRemovedJoinWouldHaveNamed() {
        WorkspaceMembershipDto owner = workspaceService.createWorkspace("Stale Owner", currentUser.getId());
        WorkspaceMembershipDto sibling = createSiblingWorkspace(owner, "Stale Sibling");
        Company company = companyIn(owner.getId());
        Organization otherOrg = new Organization();
        otherOrg.setName("Stale Other Org");
        otherOrg.setSlug("stale-other-org-" + unique());
        organizationMapper.insert(otherOrg);
        Workspace outside = new Workspace();
        outside.setOrgId(otherOrg.getId());
        outside.setName("Absent From Snapshot");
        outside.setSlug("absent-" + unique());
        workspaceMapper.insert(outside);

        authenticateAs(currentUser, owner.getId());
        shareService.share("company", company.getId(), sibling.getId(), false);
        jdbcTemplate.update(
            "INSERT INTO company_share (company_id, workspace_id, granted_by, can_edit) VALUES (?, ?, ?, ?)",
            company.getId(), outside.getId(), currentUser.getId(), false);

        List<ShareDto> shares = shareService.listShares("company", company.getId());

        assertEquals(List.of(sibling.getId()),
            shares.stream().map(ShareDto::getWorkspaceId).toList());
        assertEquals("Stale Sibling", shares.getFirst().getWorkspaceName());
    }

    @Test
    void provisionCeasedPersonBlocksNewShareButStillAllowsUnshare() {
        WorkspaceMembershipDto owner = workspaceService.createWorkspace("Person Owner WS", currentUser.getId());
        WorkspaceMembershipDto existingTarget = createSiblingWorkspace(owner, "Existing Target WS");
        Person person = new Person();
        person.setWorkspaceId(owner.getId());
        person.setName("Provision ceased " + unique());
        personMapper.insert(person);

        shareService.share("person", person.getId(), existingTarget.getId(), false);
        WorkspaceMembershipDto blockedTarget = createSiblingWorkspace(owner, "Blocked Target WS");
        personMapper.updateProcessingRestrictions(owner.getId(), person.getId(), false, true);

        assertEquals(0, shareMapper.sharePerson(
            person.getId(), owner.getId(), blockedTarget.getId(), currentUser.getId(), false,
                orgWorkspaceIdsJson(workspaceMapper, owner.getId())));
        ShareBlockedPrivacyHoldException blocked = assertThrows(
            ShareBlockedPrivacyHoldException.class,
            () -> shareService.share("person", person.getId(), blockedTarget.getId(), false));
        assertEquals(
            "This contact asked not to be shared outside this workspace, so new shares are blocked.",
            blocked.getMessage());
        assertEquals("SHARE_BLOCKED_PRIVACY_HOLD", blocked.getCode());

        shareService.unshare("person", person.getId(), existingTarget.getId());
        assertTrue(shareService.listShares("person", person.getId()).isEmpty());
    }
}
