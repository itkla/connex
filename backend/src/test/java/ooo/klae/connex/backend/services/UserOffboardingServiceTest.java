package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSnapshot;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.UserDashboard;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.UserDashboardMapper;

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
    @Autowired private UserDashboardMapper userDashboardMapper;
    @Autowired private ReportMapper reportMapper;
    @Autowired private RuleMapper ruleMapper;

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
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 1);

        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.updateOwner(workspace.getId(), deal.getId(), target.getId());
        companyMapper.updateOwner(workspace.getId(), company.getId(), target.getId());
        personMapper.updateOwner(workspace.getId(), person.getId(), target.getId());
        dealMapper.insertCollaborators(workspace.getId(), deal.getId(), List.of(target.getId()));
        Task task = newTask(target, null, null);
        SavedView view = savedView(target);
        userDashboardMapper.upsert(dashboard(target));
        newNotification(workspace.getId(), target.getId());
        Rule rule = ruleFor(target);
        ReportDefinition reportDefinition = reportDefinitionFor(target);
        ReportSnapshot reportSnapshot = reportSnapshotFor(reportDefinition, target);

        offboardingService.eraseOrgDataReferences(target.getId());

        assertNull(dealMapper.getDealById(workspace.getId(), deal.getId()).getOwnerId());
        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());
        assertTrue(dealMapper.getCollaborators(workspace.getId(), deal.getId()).isEmpty());
        assertNull(taskMapper.getTaskById(workspace.getId(), task.getId()).getAssignedTo());
        assertNull(savedViewMapper.getById(workspace.getId(), target.getId(), view.getId()));
        assertNull(userDashboardMapper.getByWorkspaceAndUser(workspace.getId(), target.getId()));
        assertEquals(0, notificationMapper.countPage(target.getId(), null, null, null, null));
        Rule after = ruleMapper.getById(workspace.getId(), rule.getId());
        assertNull(after.getRunAsUserId());
        assertNull(after.getCreatedById());
        assertNull(reportMapper.getDefinition(workspace.getId(), reportDefinition.getId()).getCreatedBy());
        assertNull(reportMapper.getSnapshot(
            workspace.getId(), reportDefinition.getId(), reportSnapshot.getId()).getGeneratedBy());
        assertEquals(target.getDisplayName(), userMapper.getUserById(target.getId()).getDisplayName());
    }

    @Test
    void memberDetachmentCleansContentWhileTheMembershipStillExists() {
        User member = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Deal deal = newDeal(pipeline, newStage(pipeline, 1), company);
        dealMapper.updateOwner(workspace.getId(), deal.getId(), member.getId());
        companyMapper.updateOwner(workspace.getId(), company.getId(), member.getId());
        personMapper.updateOwner(workspace.getId(), person.getId(), member.getId());
        dealMapper.insertCollaborators(workspace.getId(), deal.getId(), List.of(member.getId()));
        newNotification(workspace.getId(), member.getId());
        Task task = newTask(member, null, null);

        offboardingService.detachMemberContent(workspace.getId(), member.getId());

        assertTrue(dealMapper.getCollaborators(workspace.getId(), deal.getId()).isEmpty());
        assertNull(dealMapper.getDealById(workspace.getId(), deal.getId()).getOwnerId());
        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()).getOwnerId());
        assertNull(personMapper.getPersonById(workspace.getId(), person.getId()).getOwnerId());
        assertEquals(0, notificationMapper.countPage(member.getId(), null, null, null, null));
        assertNull(taskMapper.getTaskById(workspace.getId(), task.getId()).getAssignedTo());
        assertTrue(workspaceMapper.isMember(workspace.getId(), member.getId()));
    }

    private SavedView savedView(User owner) {
        SavedView view = new SavedView();
        view.setWorkspaceId(workspace.getId());
        view.setUserId(owner.getId());
        view.setRecordType("company");
        view.setName("View " + unique());
        view.setConfigJson("{}");
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
}
