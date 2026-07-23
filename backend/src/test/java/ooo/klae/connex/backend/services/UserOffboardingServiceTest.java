package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.ContactChannelConsent;
import ooo.klae.connex.backend.beans.ContactChannelConsentEvent;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSnapshot;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.SuppressionEntry;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.UserDashboard;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.SavedViewPreferenceMapper;
import ooo.klae.connex.backend.mappers.SuppressionMapper;
import ooo.klae.connex.backend.mappers.UserDashboardMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the service-layer offboarding fan-out does what the dropped
 * cross-plane foreign keys used to (#440 increment 3): the RESTRICT-mirroring
 * guard refuses deletion while authored content exists, and the erasure
 * detaches/deletes every org-data reference without relying on the database —
 * the assertions run while the user row still exists, so no FK can have fired.
 */
class UserOffboardingServiceTest extends AbstractServiceTest {

    @Autowired private UserOffboardingService offboardingService;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private SavedViewMapper savedViewMapper;
    @Autowired private SavedViewPreferenceMapper savedViewPreferenceMapper;
    @Autowired private UserDashboardMapper userDashboardMapper;
    @Autowired private ReportMapper reportMapper;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private ConsentMapper consentMapper;
    @Autowired private SuppressionMapper suppressionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void authoredNoteRefusesDeletion() {
        User target = newUser();
        newNote(target, null, null);
        assertThrows(ConflictException.class, () -> offboardingService.assertNoAuthoredContent(target.getId()));
    }

    @Test
    void createdActivityRefusesDeletion() {
        User target = newUser();
        newActivity(target, null, null);
        assertThrows(ConflictException.class, () -> offboardingService.assertNoAuthoredContent(target.getId()));
    }

    @Test
    void cleanAccountPassesTheGuard() {
        User target = newUser();
        offboardingService.assertNoAuthoredContent(target.getId());
    }

    @Test
    void erasureDetachesAndDeletesEveryReferenceWhileTheUserStillExists() {
        User target = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        companyMapper.updateOwner(workspace.getId(), company.getId(), target.getId());
        personMapper.updateOwner(workspace.getId(), person.getId(), target.getId());
        Workspace otherWorkspace = newOtherWorkspace();
        Company otherCompany = companyInWorkspace(otherWorkspace, target.getId());
        Person otherPerson = personInWorkspace(otherWorkspace, target.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 1);

        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.updateOwner(workspace.getId(), deal.getId(), target.getId());
        dealMapper.insertCollaborators(workspace.getId(), deal.getId(), List.of(target.getId()));
        Task task = newTask(target, null, null);
        SavedView view = savedView(target);
        SavedView anotherView = savedView(newUser());
        savedViewPreferenceMapper.insertPin(workspace.getId(), target.getId(), anotherView.getId(), 0);
        savedViewPreferenceMapper.upsertDefault(
            workspace.getId(), target.getId(), "company", anotherView.getId());
        userDashboardMapper.upsert(dashboard(target));
        newNotification(workspace.getId(), target.getId());
        Rule rule = ruleFor(target);
        ReportDefinition reportDefinition = reportDefinitionFor(target);
        ReportSnapshot reportSnapshot = reportSnapshotFor(reportDefinition, target);
        Campaign campaign = campaignFor(target);
        CampaignAudienceSnapshot campaignSnapshot = campaignSnapshotFor(campaign, target);
        ContactChannelConsentEvent consentEvent = consentEventFor(newPerson(company), target);
        SuppressionEntry suppression = suppressionFor(target);

        offboardingService.eraseOrgDataReferences(target.getId());

        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());
        assertNull(companyMapper.getCompanyById(
            otherWorkspace.getId(), otherCompany.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(
            otherWorkspace.getId(), otherPerson.getId()).getOwnerId());
        assertNull(dealMapper.getDealById(workspace.getId(), deal.getId()).getOwnerId());
        assertTrue(dealMapper.getCollaboratorIds(workspace.getId(), deal.getId()).isEmpty());
        assertNull(taskMapper.getTaskById(workspace.getId(), task.getId()).getAssignedTo());
        assertNull(savedViewMapper.getAccessibleById(workspace.getId(), target.getId(), view.getId()));
        assertNull(savedViewPreferenceMapper.getPin(
            workspace.getId(), target.getId(), anotherView.getId()));
        assertNull(savedViewPreferenceMapper.getDefault(workspace.getId(), target.getId(), "company"));
        assertNull(userDashboardMapper.getByWorkspaceAndUser(workspace.getId(), target.getId()));
        assertEquals(0, notificationMapper.countPage(target.getId(), null, null, null, null));
        Rule after = ruleMapper.getById(workspace.getId(), rule.getId());
        assertNull(after.getRunAsUserId());
        assertNull(after.getCreatedById());
        assertNull(reportMapper.getDefinition(workspace.getId(), reportDefinition.getId()).getCreatedBy());
        assertNull(reportMapper.getSnapshot(
            workspace.getId(), reportDefinition.getId(), reportSnapshot.getId()).getGeneratedBy());
        Campaign clearedCampaign = campaignMapper.getCampaign(workspace.getId(), campaign.getId());
        assertNull(clearedCampaign.getOwnerUserId());
        assertNull(clearedCampaign.getCreatedById());
        assertNull(campaignMapper.getSnapshot(
            workspace.getId(), campaign.getId(), campaignSnapshot.getVersion()).getCreatedById());
        assertNull(jdbcTemplate.queryForObject(
            "SELECT created_by_id FROM contact_channel_consent_event WHERE id = ?",
            Integer.class, consentEvent.getId()));
        assertNull(suppressionMapper.getById(workspace.getId(), suppression.getId()).getCreatedById());
        assertEquals(target.getDisplayName(), userMapper.getUserById(target.getId()).getDisplayName());
    }

    @Test
    void memberDetachmentCleansContentWhileTheMembershipStillExists() {
        User member = newUser();
        Pipeline pipeline = newPipeline();
        Company company = newCompany();
        Person person = newPerson(company);
        Deal deal = newDeal(pipeline, newStage(pipeline, 1), company);
        companyMapper.updateOwner(workspace.getId(), company.getId(), member.getId());
        personMapper.updateOwner(workspace.getId(), person.getId(), member.getId());
        dealMapper.updateOwner(workspace.getId(), deal.getId(), member.getId());
        Workspace otherWorkspace = newOtherWorkspace();
        Company otherCompany = companyInWorkspace(otherWorkspace, member.getId());
        Person otherPerson = personInWorkspace(otherWorkspace, member.getId());
        dealMapper.insertCollaborators(workspace.getId(), deal.getId(), List.of(member.getId()));
        newNotification(workspace.getId(), member.getId());
        Task task = newTask(member, null, null);
        Campaign campaign = campaignFor(member);
        SavedView memberView = savedView(member);
        SavedView retainedOtherWorkspaceView = savedViewInWorkspace(otherWorkspace, member);
        SavedView sharedTarget = savedView(newUser());
        savedViewPreferenceMapper.insertPin(workspace.getId(), member.getId(), sharedTarget.getId(), 0);
        savedViewPreferenceMapper.upsertDefault(
            workspace.getId(), member.getId(), "company", sharedTarget.getId());

        offboardingService.detachMemberContent(workspace.getId(), member.getId());

        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());
        assertNull(dealMapper.getDealById(workspace.getId(), deal.getId()).getOwnerId());
        assertEquals(member.getId(),
            companyMapper.getCompanyById(otherWorkspace.getId(), otherCompany.getId()).getOwnerId());
        assertEquals(member.getId(),
            personMapper.getPersonById(otherWorkspace.getId(), otherPerson.getId()).getOwnerId());
        assertTrue(dealMapper.getCollaboratorIds(workspace.getId(), deal.getId()).isEmpty());
        assertEquals(0, notificationMapper.countPage(member.getId(), null, null, null, null));
        assertNull(taskMapper.getTaskById(workspace.getId(), task.getId()).getAssignedTo());
        assertNull(campaignMapper.getCampaign(workspace.getId(), campaign.getId()).getOwnerUserId());
        assertNull(savedViewMapper.getAccessibleById(
            workspace.getId(), member.getId(), memberView.getId()));
        assertNull(savedViewPreferenceMapper.getPin(
            workspace.getId(), member.getId(), sharedTarget.getId()));
        assertNull(savedViewPreferenceMapper.getDefault(workspace.getId(), member.getId(), "company"));
        assertNotNull(savedViewMapper.getAccessibleById(
            otherWorkspace.getId(), member.getId(), retainedOtherWorkspaceView.getId()));
        assertTrue(workspaceMapper.isMember(workspace.getId(), member.getId()));
    }

    @Test
    void freshMembershipPurgesResidualSavedViewDataOnlyInTheRejoinedWorkspace() {
        User returning = newUser();
        SavedView residualView = savedView(returning);
        SavedView sharedTarget = savedView(newUser());
        savedViewPreferenceMapper.insertPin(
            workspace.getId(), returning.getId(), sharedTarget.getId(), 0);
        savedViewPreferenceMapper.upsertDefault(
            workspace.getId(), returning.getId(), "company", sharedTarget.getId());
        Workspace otherWorkspace = newOtherWorkspace();
        SavedView retainedView = savedViewInWorkspace(otherWorkspace, returning);
        workspaceMapper.removeMember(workspace.getId(), returning.getId());

        offboardingService.prepareFreshMembership(workspace.getId(), returning.getId());

        assertNull(savedViewMapper.getAccessibleById(
            workspace.getId(), returning.getId(), residualView.getId()));
        assertNull(savedViewPreferenceMapper.getPin(
            workspace.getId(), returning.getId(), sharedTarget.getId()));
        assertNull(savedViewPreferenceMapper.getDefault(
            workspace.getId(), returning.getId(), "company"));
        assertNotNull(savedViewMapper.getAccessibleById(
            otherWorkspace.getId(), returning.getId(), retainedView.getId()));
    }

    private Workspace newOtherWorkspace() {
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        return other;
    }

    private Company companyInWorkspace(Workspace target, int ownerId) {
        Company company = new Company();
        company.setWorkspaceId(target.getId());
        company.setOwnerId(ownerId);
        company.setName("Company " + unique());
        companyMapper.insert(company);
        return company;
    }

    private Person personInWorkspace(Workspace target, int ownerId) {
        Person person = new Person();
        person.setWorkspaceId(target.getId());
        person.setOwnerId(ownerId);
        person.setName("Person " + unique());
        person.setEmail(unique() + "@example.com");
        personMapper.insert(person);
        return person;
    }

    private SavedView savedView(User owner) {
        return savedViewInWorkspace(workspace, owner);
    }

    private SavedView savedViewInWorkspace(Workspace targetWorkspace, User owner) {
        SavedView view = new SavedView();
        view.setWorkspaceId(targetWorkspace.getId());
        view.setUserId(owner.getId());
        view.setRecordType("company");
        view.setName("View " + unique());
        var config = objectMapper.createObjectNode();
        config.put("version", 1);
        view.setConfig(config);
        view.setVisibility("private");
        savedViewMapper.insert(view);
        return view;
    }

    private UserDashboard dashboard(User owner) {
        UserDashboard dashboard = new UserDashboard();
        dashboard.setWorkspaceId(workspace.getId());
        dashboard.setUserId(owner.getId());
        dashboard.setLayoutJson("{\"widgets\":[]}");
        return dashboard;
    }


    private Rule ruleFor(User user) {
        Rule rule = new Rule();
        rule.setWorkspaceId(workspace.getId());
        rule.setName("Rule " + unique());
        rule.setEnabled(false);
        rule.setRecordType("deal");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig("{}");
        rule.setActionsJson("[]");
        rule.setExecutionMode("user");
        rule.setRunAsUserId(user.getId());
        rule.setCreatedById(user.getId());
        ruleMapper.insert(rule);
        return rule;
    }

    private ReportDefinition reportDefinitionFor(User user) {
        ReportDefinition definition = new ReportDefinition();
        definition.setWorkspaceId(workspace.getId());
        definition.setName("Report " + unique());
        definition.setCadence("monthly");
        definition.setConfigJson("{\"widgets\":[]}");
        definition.setCreatedBy(user.getId());
        reportMapper.insertDefinition(definition);
        return definition;
    }

    private ReportSnapshot reportSnapshotFor(ReportDefinition definition, User user) {
        ReportSnapshot snapshot = new ReportSnapshot();
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setReportDefinitionId(definition.getId());
        snapshot.setPeriodStart(LocalDate.of(2026, 6, 1));
        snapshot.setPeriodEnd(LocalDate.of(2026, 6, 30));
        snapshot.setComputedResult("{}");
        snapshot.setGeneratedBy(user.getId());
        reportMapper.insertSnapshot(snapshot);
        return snapshot;
    }

    private Campaign campaignFor(User user) {
        Campaign campaign = new Campaign();
        campaign.setWorkspaceId(workspace.getId());
        campaign.setName("Campaign " + unique());
        campaign.setType("email");
        campaign.setStatus("draft");
        campaign.setOwnerUserId(user.getId());
        campaign.setCreatedById(user.getId());
        campaignMapper.insertCampaign(campaign);
        return campaign;
    }

    private CampaignAudienceSnapshot campaignSnapshotFor(Campaign campaign, User user) {
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setWorkspaceId(workspace.getId());
        snapshot.setCampaignId(campaign.getId());
        snapshot.setVersion(1);
        snapshot.setRecordType("company");
        snapshot.setDefinitionJson("{\"match\":\"all\",\"conditions\":[]}");
        snapshot.setCreatedById(user.getId());
        campaignMapper.insertSnapshot(snapshot);
        return snapshot;
    }

    private ContactChannelConsentEvent consentEventFor(Person person, User user) {
        ContactChannelConsent consent = new ContactChannelConsent();
        consent.setWorkspaceId(workspace.getId());
        consent.setPersonId(person.getId());
        consent.setChannel("email");
        consent.setPurpose("marketing");
        consent.setStatus("granted");
        consent.setSource("test");
        consentMapper.upsert(consent);
        ContactChannelConsentEvent event = new ContactChannelConsentEvent();
        event.setWorkspaceId(workspace.getId());
        event.setConsentId(consent.getId());
        event.setPersonId(person.getId());
        event.setChannel("email");
        event.setPurpose("marketing");
        event.setStatus("granted");
        event.setSource("test");
        event.setCreatedById(user.getId());
        consentMapper.insertEvent(event);
        return event;
    }

    private SuppressionEntry suppressionFor(User user) {
        SuppressionEntry entry = new SuppressionEntry();
        entry.setWorkspaceId(workspace.getId());
        entry.setScope("workspace");
        entry.setChannel("email");
        entry.setAddress(unique() + "@example.com");
        entry.setReason("manual");
        entry.setCreatedById(user.getId());
        suppressionMapper.insert(entry);
        return entry;
    }
}
