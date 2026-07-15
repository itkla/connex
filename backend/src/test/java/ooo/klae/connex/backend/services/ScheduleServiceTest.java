package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.ReportSchedule;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.ReportConfig;
import ooo.klae.connex.backend.dto.ReportDefinitionDto;
import ooo.klae.connex.backend.dto.ReportDefinitionRequest;
import ooo.klae.connex.backend.dto.ReportDocumentDto;
import ooo.klae.connex.backend.dto.ReportFilters;
import ooo.klae.connex.backend.dto.ReportLayoutItem;
import ooo.klae.connex.backend.dto.ReportScheduleDto;
import ooo.klae.connex.backend.dto.ReportScheduleRequest;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ScheduleMapper;

class ScheduleServiceTest extends AbstractServiceTest {

    @Autowired private ScheduleService scheduleService;
    @Autowired private ScheduleMapper scheduleMapper;
    @Autowired private ReportService reportService;
    @Autowired private RoleService roleService;
    @Autowired private WorkspaceService workspaceService;

    @Test
    void crudResolvesMembersAndClaimsAnOccurrenceAtMostOnce() {
        User recipient = newUser();
        int reportId = createReport("count").id();

        ReportScheduleDto created = scheduleService.create(
                reportId, request("weekly", List.of(recipient.getId()), 9, true));

        assertEquals(currentUser.getId(), created.runAsUserId());
        assertEquals(List.of(recipient.getId()), created.recipientUserIds());
        assertEquals(recipient.getDisplayName(), created.recipients().getFirst().displayName());
        assertEquals(created.id(), scheduleService.get(reportId).id());

        ReportSchedule stored = scheduleMapper.getByReport(workspace.getId(), reportId);
        LocalDateTime dueAt = LocalDateTime.of(2026, 7, 13, 9, 0);
        stored.setNextRunAt(dueAt.minusMinutes(1));
        assertEquals(1, scheduleMapper.update(stored));

        ReportSchedule claimed = scheduleService.claimDue(stored.getId(), currentUser.getId(), dueAt);

        assertNotNull(claimed);
        assertEquals(dueAt, claimed.getLastRunAt());
        assertTrue(claimed.getNextRunAt().isAfter(dueAt));
        assertNull(scheduleService.claimDue(stored.getId(), currentUser.getId(), dueAt));

        ReportScheduleDto updated = scheduleService.update(
                reportId, request("monthly", List.of(currentUser.getId()), 15, false));
        assertEquals("monthly", updated.cadence());
        assertEquals(currentUser.getId(), updated.runAsUserId());
        assertEquals(false, updated.enabled());

        scheduleService.delete(reportId);
        assertThrows(ResourceNotFoundException.class, () -> scheduleService.get(reportId));
    }

    @Test
    void createRejectsARecipientOutsideTheWorkspace() {
        User nonMember = detachedUser();
        int reportId = createReport("count").id();

        assertThrows(BadRequestException.class, () -> scheduleService.create(
                reportId, request("weekly", List.of(nonMember.getId()), 9, true)));
    }

    @Test
    void reportReadCanViewButCannotManageSchedules() {
        int reportId = createReport("count").id();
        scheduleService.create(reportId, request("weekly", List.of(currentUser.getId()), 9, true));
        User reader = newUser();
        WorkspaceRole readOnly = roleService.createRole(
                workspace.getId(), currentUser.getId(), "Report reader " + unique(), List.of("REPORT_READ"));
        workspaceService.assignCustomRole(
                workspace.getId(), currentUser.getId(), reader.getId(), readOnly.getId());
        authenticateAs(reader, workspace.getId());

        assertEquals(reportId, scheduleService.get(reportId).reportDefinitionId());
        assertThrows(ForbiddenException.class, () -> scheduleService.update(
                reportId, request("monthly", List.of(reader.getId()), 10, true)));
        assertThrows(ForbiddenException.class, () -> scheduleService.delete(reportId));
    }

    @Test
    void reportManagerCanScheduleWithoutSnapshotCreateAccess() {
        int reportId = createReport("count").id();
        User manager = newUser();
        userMapper.updateLocale(manager.getId(), "ja");
        WorkspaceRole reportManager = roleService.createRole(
                workspace.getId(), currentUser.getId(), "Report manager " + unique(),
                List.of("REPORT_READ", "REPORT_UPDATE"));
        workspaceService.assignCustomRole(
                workspace.getId(), currentUser.getId(), manager.getId(), reportManager.getId());
        authenticateAs(manager, workspace.getId());

        ReportScheduleDto created = scheduleService.create(
                reportId, request("weekly", List.of(manager.getId()), 9, true));
        ReportSchedule stored = scheduleMapper.getByReport(workspace.getId(), reportId);
        ReportDocumentDto document = reportService.generate(reportId, null);

        assertEquals(manager.getId(), created.runAsUserId());
        assertTrue(scheduleService.deliveryAccess(stored).allowed());
        assertEquals(
                List.of(manager.getId()),
                scheduleService.activeReportReaders(stored).stream().map(User::getId).toList());
        List<User> recipients = scheduleService.activeRecipientsForDocument(stored, document);
        assertEquals(List.of(manager.getId()), recipients.stream().map(User::getId).toList());
        assertEquals("ja", recipients.getFirst().getLocale());
    }

    @Test
    void scheduleInAnotherWorkspaceIsNotVisible() {
        int reportId = createReport("count").id();
        scheduleService.create(reportId, request("weekly", List.of(currentUser.getId()), 9, true));
        Workspace other = new Workspace();
        other.setName("Other " + unique());
        other.setSlug("other-" + unique());
        other.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(other);
        User otherOwner = detachedUser();
        workspaceMapper.addMember(other.getId(), otherOwner.getId(), "owner");
        authenticateAs(otherOwner, other.getId());

        assertThrows(ResourceNotFoundException.class, () -> scheduleService.get(reportId));
    }

    @Test
    void attainmentScheduleChecksRunAsBeforeRecipientAccess() {
        int reportId = createReport("attainment").id();
        User recipient = newUser();
        assignRole(recipient, "Report reader", List.of("REPORT_READ"));
        User manager = newUser();
        assignRole(manager, "Report manager", List.of("REPORT_READ", "REPORT_UPDATE"));
        authenticateAs(manager, workspace.getId());

        assertThrows(ForbiddenException.class, () -> scheduleService.create(
                reportId, request("monthly", List.of(recipient.getId()), 9, true)));
    }

    @Test
    void attainmentScheduleRejectsRecipientWithoutGoalReadOnCreate() {
        int reportId = createReport("attainment").id();
        User recipient = newUser();
        assignRole(recipient, "Report reader", List.of("REPORT_READ"));

        BadRequestException exception = assertThrows(BadRequestException.class, () -> scheduleService.create(
                reportId, request("monthly", List.of(recipient.getId()), 9, true)));

        assertEquals("Report recipients lack permissions required by this report", exception.getMessage());
    }

    @Test
    void attainmentScheduleRejectsRecipientWithoutGoalReadOnUpdate() {
        int reportId = createReport("attainment").id();
        User eligible = newUser();
        assignRole(eligible, "Attainment reader", List.of("REPORT_READ", "GOAL_READ"));
        User ineligible = newUser();
        assignRole(ineligible, "Report reader", List.of("REPORT_READ"));
        scheduleService.create(reportId, request("monthly", List.of(eligible.getId()), 9, true));

        assertThrows(BadRequestException.class, () -> scheduleService.update(
                reportId, request("quarterly", List.of(ineligible.getId()), 10, true)));

        ReportScheduleDto unchanged = scheduleService.get(reportId);
        assertEquals("monthly", unchanged.cadence());
        assertEquals(List.of(eligible.getId()), unchanged.recipientUserIds());
    }

    @Test
    void attainmentDeliveryExcludesRecipientAfterGoalReadLoss() {
        int reportId = createReport("attainment").id();
        User recipient = newUser();
        WorkspaceRole recipientRole = assignRole(
                recipient, "Attainment reader", List.of("REPORT_READ", "GOAL_READ"));
        scheduleService.create(reportId, request("monthly", List.of(recipient.getId()), 9, true));
        roleService.updateRole(
                workspace.getId(), currentUser.getId(), recipientRole.getId(), recipientRole.getName(),
                List.of("REPORT_READ"));
        ReportSchedule stored = scheduleMapper.getByReport(workspace.getId(), reportId);
        ReportDocumentDto document = reportService.generate(reportId, null);

        assertEquals(
                List.of(recipient.getId()),
                scheduleService.activeReportReaders(stored).stream().map(User::getId).toList());
        assertTrue(scheduleService.activeRecipientsForDocument(stored, document).isEmpty());
    }

    @Test
    void exactDocumentAuthorizationUsesLatestScheduleRecipients() {
        int reportId = createReport("count").id();
        User removed = newUser();
        User replacement = newUser();
        scheduleService.create(reportId, request("monthly", List.of(removed.getId()), 9, true));
        ReportSchedule claimed = scheduleMapper.getByReport(workspace.getId(), reportId);
        ReportDocumentDto document = reportService.generate(reportId, null);

        scheduleService.update(reportId, request("monthly", List.of(replacement.getId()), 9, true));

        assertEquals(
                List.of(replacement.getId()),
                scheduleService.activeReportReaders(claimed).stream().map(User::getId).toList());
        assertEquals(
                List.of(replacement.getId()),
                scheduleService.activeRecipientsForDocument(claimed, document).stream().map(User::getId).toList());
    }

    private ReportDefinitionDto createReport(String measure) {
        ReportWidgetConfig widget = new ReportWidgetConfig(
                "summary", "Summary", "deals", measure, "none", "kpi");
        ReportConfig config = new ReportConfig(
                List.of(widget), new ReportFilters(null, null, null, null, null), null, "day",
                List.of(new ReportLayoutItem("summary", 0, 0, 6, 4)));
        return reportService.create(new ReportDefinitionRequest(
                "Scheduled " + unique(), "Delivery test", "monthly", null, config));
    }

    private static ReportScheduleRequest request(
            String cadence,
            List<Integer> recipients,
            int hour,
            boolean enabled) {
        return new ReportScheduleRequest(cadence, recipients, "UTC", hour, enabled);
    }

    private WorkspaceRole assignRole(User member, String name, List<String> permissions) {
        WorkspaceRole role = roleService.createRole(
                workspace.getId(), currentUser.getId(), name + " " + unique(), permissions);
        workspaceService.assignCustomRole(
                workspace.getId(), currentUser.getId(), member.getId(), role.getId());
        return role;
    }

    private User detachedUser() {
        String suffix = unique();
        User user = new User();
        user.setUsername("detached_" + suffix);
        user.setDisplayName("Detached " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash("hash_" + suffix);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
