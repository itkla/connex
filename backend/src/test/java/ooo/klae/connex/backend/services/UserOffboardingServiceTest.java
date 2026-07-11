package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.UserDashboardMapper;

import java.util.List;

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
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 1);

        Deal deal = newDeal(pipeline, stage, company);
        dealMapper.updateOwner(workspace.getId(), deal.getId(), target.getId());
        dealMapper.insertCollaborators(workspace.getId(), deal.getId(), List.of(target.getId()));
        Task task = newTask(target, null, null);
        SavedView view = savedView(target);
        userDashboardMapper.upsert(dashboard(target));
        notificationMapper.upsert(notificationFor(target));
        Rule rule = ruleFor(target);

        offboardingService.eraseOrgDataReferences(target.getId());

        assertNull(dealMapper.getDealById(workspace.getId(), deal.getId()).getOwnerId());
        assertTrue(dealMapper.getCollaborators(workspace.getId(), deal.getId()).isEmpty());
        assertNull(taskMapper.getTaskById(workspace.getId(), task.getId()).getAssignedTo());
        assertNull(savedViewMapper.getById(workspace.getId(), target.getId(), view.getId()));
        assertNull(userDashboardMapper.getByWorkspaceAndUser(workspace.getId(), target.getId()));
        assertEquals(0, notificationMapper.countPage(target.getId(), null, null, null, null));
        Rule after = ruleMapper.getById(workspace.getId(), rule.getId());
        assertNull(after.getRunAsUserId());
        assertNull(after.getCreatedById());
        assertEquals(target.getDisplayName(), userMapper.getUserById(target.getId()).getDisplayName());
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

    private ooo.klae.connex.backend.beans.UserDashboard dashboard(User owner) {
        ooo.klae.connex.backend.beans.UserDashboard dashboard = new ooo.klae.connex.backend.beans.UserDashboard();
        dashboard.setWorkspaceId(workspace.getId());
        dashboard.setUserId(owner.getId());
        dashboard.setLayoutJson("{\"widgets\":[]}");
        return dashboard;
    }

    private Notification notificationFor(User recipient) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspace.getId());
        notification.setRecipientId(recipient.getId());
        notification.setType("task.assigned");
        notification.setCategory("tasks");
        notification.setSeverity("info");
        notification.setTemplateVersion(1);
        notification.setTitle("Task assigned");
        notification.setDedupeKey("offboarding-" + unique());
        notification.setTriggeredAt("2026-07-11 00:00:00");
        return notification;
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
}
