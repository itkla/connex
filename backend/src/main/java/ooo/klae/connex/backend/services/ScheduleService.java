package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSchedule;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ReportDocumentDto;
import ooo.klae.connex.backend.dto.ReportScheduleDto;
import ooo.klae.connex.backend.dto.ReportScheduleRecipientDto;
import ooo.klae.connex.backend.dto.ReportScheduleRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.ScheduleMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Business logic for workspace-scoped scheduled report delivery. */
@Service
@RequiredArgsConstructor
public class ScheduleService {
    private static final Set<String> CADENCES = Set.of("weekly", "monthly", "quarterly");

    private final ScheduleMapper scheduleMapper;
    private final ReportMapper reportMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;
    private final TenantWorkScope tenantWorkScope;
    private final ReportPermissionPolicy reportPermissionPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Returns the delivery schedule for a report in the active workspace. */
    @RequirePermission(Permission.REPORT_READ)
    public ReportScheduleDto get(int reportDefinitionId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDefinition(workspaceId, reportDefinitionId);
        return toDto(requireSchedule(workspaceId, reportDefinitionId), activeMembers(workspaceId));
    }

    /** Creates the single delivery schedule for a report. */
    @Transactional
    @RequirePermission(Permission.REPORT_UPDATE)
    public ReportScheduleDto create(int reportDefinitionId, ReportScheduleRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ReportDefinition definition = requireDefinition(workspaceId, reportDefinitionId);
        Set<Permission> requiredPermissions = reportPermissionPolicy.requiredFor(definition);
        int currentUserId = authService.getCurrentUser().getId();
        requireReportPermissions(workspaceId, currentUserId, requiredPermissions);
        ValidatedSchedule validated = validate(workspaceId, request, requiredPermissions);

        ReportSchedule schedule = new ReportSchedule();
        schedule.setWorkspaceId(workspaceId);
        schedule.setReportDefinitionId(reportDefinitionId);
        schedule.setRunAsUserId(currentUserId);
        schedule.setCreatedBy(currentUserId);
        apply(schedule, validated);
        try {
            scheduleMapper.insert(schedule);
        } catch (DuplicateKeyException exception) {
            throw duplicateSchedule();
        }
        auditService.record("report.schedule.create", "report_schedule", schedule.getId(), definition.getName(),
                "Created report delivery schedule", null);
        return toDto(requireSchedule(workspaceId, reportDefinitionId), activeMembers(workspaceId));
    }

    /** Replaces a report's delivery schedule and transfers run-as to the updater. */
    @Transactional
    @RequirePermission(Permission.REPORT_UPDATE)
    public ReportScheduleDto update(int reportDefinitionId, ReportScheduleRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ReportDefinition definition = requireDefinition(workspaceId, reportDefinitionId);
        ReportSchedule schedule = requireSchedule(workspaceId, reportDefinitionId);
        Set<Permission> requiredPermissions = reportPermissionPolicy.requiredFor(definition);
        int currentUserId = authService.getCurrentUser().getId();
        requireReportPermissions(workspaceId, currentUserId, requiredPermissions);
        ValidatedSchedule validated = validate(workspaceId, request, requiredPermissions);

        schedule.setRunAsUserId(currentUserId);
        apply(schedule, validated);
        try {
            if (scheduleMapper.update(schedule) == 0) {
                throw new ResourceNotFoundException("Report delivery schedule not found");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateSchedule();
        }
        auditService.record("report.schedule.update", "report_schedule", schedule.getId(), definition.getName(),
                "Updated report delivery schedule", null);
        return toDto(requireSchedule(workspaceId, reportDefinitionId), activeMembers(workspaceId));
    }

    /** Deletes a report's delivery schedule. */
    @Transactional
    @RequirePermission(Permission.REPORT_UPDATE)
    public void delete(int reportDefinitionId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ReportDefinition definition = requireDefinition(workspaceId, reportDefinitionId);
        ReportSchedule schedule = requireSchedule(workspaceId, reportDefinitionId);
        if (scheduleMapper.deleteByReport(workspaceId, reportDefinitionId) == 0) {
            throw new ResourceNotFoundException("Report delivery schedule not found");
        }
        auditService.record("report.schedule.delete", "report_schedule", schedule.getId(), definition.getName(),
                "Deleted report delivery schedule", null);
    }

    /** Loads one schedule under an explicitly pinned catalog for scheduler orchestration. */
    public ReportSchedule loadForDelivery(int workspaceId, int scheduleId) {
        return scheduleMapper.getById(workspaceId, scheduleId);
    }

    /**
     * Resolves and validates the real active member used for delivery. Control-plane
     * membership and permission reads are explicitly unrouted from the tenant catalog.
     */
    public DeliveryAccess deliveryAccess(ReportSchedule schedule) {
        if (schedule == null) {
            return DeliveryAccess.denied("schedule no longer exists");
        }
        ReportDefinition definition = reportMapper.getDefinition(
                schedule.getWorkspaceId(), schedule.getReportDefinitionId());
        if (definition == null) {
            return DeliveryAccess.denied("report no longer exists");
        }
        ControlAccess control = tenantWorkScope.unrouted(() -> controlAccess(
                schedule.getWorkspaceId(), schedule.getRunAsUserId()));
        if (control.user() == null || control.role() == null) {
            return DeliveryAccess.denied("run-as user is not an active workspace member");
        }
        Set<Permission> required;
        try {
            required = reportPermissionPolicy.requiredFor(definition);
        } catch (BadRequestException exception) {
            return DeliveryAccess.denied("report definition configuration is invalid");
        }
        if (!control.permissions().containsAll(required)) {
            return DeliveryAccess.denied("run-as user lacks required report permissions");
        }
        return DeliveryAccess.allowed(control.user(), control.role());
    }

    /**
     * Atomically claims a due occurrence for the expected run-as member. The short
     * transaction commits before report generation or email enqueueing.
     */
    @Transactional
    public ReportSchedule claimDue(
            int scheduleId,
            int expectedRunAsUserId,
            LocalDateTime now) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ReportSchedule schedule = scheduleMapper.lockById(workspaceId, scheduleId);
        if (!isClaimable(schedule, expectedRunAsUserId, now)) {
            return null;
        }
        LocalDateTime nextRunAt = ReportScheduleCalculator.next(
                schedule.getCadence(), schedule.getTimezone(), schedule.getHourOfDay(),
                schedule.getNextRunAt(), now.toInstant(ZoneOffset.UTC));
        if (scheduleMapper.markClaimed(workspaceId, scheduleId, nextRunAt, now) == 0) {
            return null;
        }
        schedule.setNextRunAt(nextRunAt);
        schedule.setLastRunAt(now);
        return schedule;
    }

    /** Advances a due schedule that cannot establish its stored run-as identity. */
    @Transactional
    public boolean skipDue(
            int workspaceId,
            int scheduleId,
            int expectedRunAsUserId,
            LocalDateTime now) {
        ReportSchedule schedule = scheduleMapper.lockById(workspaceId, scheduleId);
        if (!isClaimable(schedule, expectedRunAsUserId, now)) {
            return false;
        }
        LocalDateTime nextRunAt = ReportScheduleCalculator.next(
                schedule.getCadence(), schedule.getTimezone(), schedule.getHourOfDay(),
                schedule.getNextRunAt(), now.toInstant(ZoneOffset.UTC));
        return scheduleMapper.markSkipped(workspaceId, scheduleId, nextRunAt) > 0;
    }

    /** Returns recipients eligible for the exact generated document being delivered. */
    public List<User> activeRecipientsForDocument(ReportSchedule schedule, ReportDocumentDto document) {
        if (schedule == null || document == null || document.definition() == null
                || document.definition().id() != schedule.getReportDefinitionId()) {
            throw new BadRequestException("Generated report does not match delivery schedule");
        }
        ReportSchedule current = currentDeliverySchedule(schedule);
        if (current == null) {
            return List.of();
        }
        return activeRecipients(current, reportPermissionPolicy.requiredFor(document));
    }

    /** Returns currently active recipients with baseline report-read access. */
    public List<User> activeReportReaders(ReportSchedule schedule) {
        ReportSchedule current = currentDeliverySchedule(schedule);
        return current == null
                ? List.of()
                : activeRecipients(current, Set.of(Permission.REPORT_READ));
    }

    /** Returns whether the claimed occurrence still belongs to the active saved schedule. */
    public boolean isCurrentDeliverySchedule(ReportSchedule schedule) {
        return currentDeliverySchedule(schedule) != null;
    }

    private List<User> activeRecipients(ReportSchedule schedule, Set<Permission> requiredPermissions) {
        if (requiredPermissions == null || !requiredPermissions.contains(Permission.REPORT_READ)) {
            throw new BadRequestException("Report recipient permissions are invalid");
        }
        Set<Permission> immutableRequiredPermissions = Set.copyOf(requiredPermissions);
        Set<Integer> recipientIds = Set.copyOf(readRecipientIds(schedule.getRecipientUserIds()));
        int workspaceId = schedule.getWorkspaceId();
        return tenantWorkScope.unrouted(() -> workspaceService.getMembers(workspaceId).stream()
                .filter(user -> recipientIds.contains(user.getId()))
                .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
                .filter(user -> hasReportPermissions(workspaceId, user.getId(), immutableRequiredPermissions))
                .toList());
    }

    private ReportSchedule currentDeliverySchedule(ReportSchedule claimed) {
        if (claimed == null) {
            return null;
        }
        ReportSchedule current = scheduleMapper.getById(claimed.getWorkspaceId(), claimed.getId());
        if (current == null
                || !current.isEnabled()
                || current.getReportDefinitionId() != claimed.getReportDefinitionId()
                || current.getRunAsUserId() != claimed.getRunAsUserId()) {
            return null;
        }
        return current;
    }

    private ValidatedSchedule validate(
            int workspaceId,
            ReportScheduleRequest request,
            Set<Permission> requiredPermissions) {
        if (request == null) {
            throw new BadRequestException("Report delivery schedule is required");
        }
        String cadence = normalize(request.cadence());
        if (!CADENCES.contains(cadence)) {
            throw new BadRequestException("Invalid report schedule cadence: " + request.cadence());
        }
        String timezone = TimezoneSupport.validateIana(request.timezone(), null);
        if (request.hourOfDay() < 0 || request.hourOfDay() > 23) {
            throw new BadRequestException("Report delivery hour must be between 0 and 23");
        }
        if (request.enabled() == null) {
            throw new BadRequestException("Report delivery enabled state is required");
        }
        List<Integer> recipientIds = request.recipientUserIds() == null
                ? List.of()
                : List.copyOf(request.recipientUserIds());
        if (recipientIds.isEmpty() || recipientIds.size() > 100
                || recipientIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BadRequestException("Choose between 1 and 100 report recipients");
        }
        if (new LinkedHashSet<>(recipientIds).size() != recipientIds.size()) {
            throw new BadRequestException("Report recipients must be unique");
        }
        Set<Integer> activeMemberIds = activeMembers(workspaceId).stream()
                .map(User::getId)
                .collect(Collectors.toUnmodifiableSet());
        if (!activeMemberIds.containsAll(recipientIds)) {
            throw new BadRequestException("Report recipients must be active workspace members");
        }
        if (recipientIds.stream().anyMatch(
                id -> !hasReportPermissions(workspaceId, id, requiredPermissions))) {
            throw new BadRequestException("Report recipients lack permissions required by this report");
        }
        LocalDateTime nextRunAt = ReportScheduleCalculator.initial(
                cadence, timezone, request.hourOfDay(), Instant.now(clock));
        return new ValidatedSchedule(
                cadence, serializeRecipientIds(recipientIds), timezone,
                request.hourOfDay(), request.enabled(), nextRunAt);
    }

    private void apply(ReportSchedule schedule, ValidatedSchedule validated) {
        schedule.setCadence(validated.cadence());
        schedule.setRecipientUserIds(validated.recipientUserIds());
        schedule.setTimezone(validated.timezone());
        schedule.setHourOfDay(validated.hourOfDay());
        schedule.setEnabled(validated.enabled());
        schedule.setNextRunAt(validated.nextRunAt());
    }

    private ReportScheduleDto toDto(ReportSchedule schedule, List<User> members) {
        Map<Integer, User> memberById = members.stream()
                .collect(Collectors.toUnmodifiableMap(User::getId, Function.identity(), (left, right) -> left));
        List<Integer> recipientIds = readRecipientIds(schedule.getRecipientUserIds());
        List<ReportScheduleRecipientDto> recipients = new ArrayList<>();
        for (int recipientId : recipientIds) {
            User member = memberById.get(recipientId);
            if (member != null) {
                recipients.add(new ReportScheduleRecipientDto(
                        member.getId(), memberLabel(member), member.getEmail()));
            }
        }
        User runAs = memberById.get(schedule.getRunAsUserId());
        return new ReportScheduleDto(
                schedule.getId(), schedule.getReportDefinitionId(), schedule.getCadence(), recipientIds,
                List.copyOf(recipients), schedule.getTimezone(), schedule.getHourOfDay(), schedule.isEnabled(),
                schedule.getRunAsUserId(), runAs == null ? null : memberLabel(runAs),
                schedule.getNextRunAt(), schedule.getLastRunAt(), schedule.getCreatedBy(),
                schedule.getCreatedAt(), schedule.getUpdatedAt());
    }

    private ControlAccess controlAccess(int workspaceId, int userId) {
        List<User> members = workspaceService.getMembers(workspaceId);
        User user = members.stream().filter(member -> member.getId() == userId).findFirst().orElse(null);
        String role = user == null ? null : workspaceService.getRole(workspaceId, userId);
        Set<Permission> permissions = user == null
                ? Set.of()
                : Set.copyOf(workspaceService.permissionsFor(workspaceId, userId));
        return new ControlAccess(user, role, permissions);
    }

    private boolean hasReportPermissions(
            int workspaceId,
            int userId,
            Set<Permission> requiredPermissions) {
        return workspaceService.permissionsFor(workspaceId, userId).containsAll(requiredPermissions);
    }

    private void requireReportPermissions(
            int workspaceId,
            int userId,
            Set<Permission> requiredPermissions) {
        for (Permission permission : requiredPermissions) {
            workspaceService.requirePermission(workspaceId, userId, permission);
        }
    }

    private ReportDefinition requireDefinition(int workspaceId, int reportDefinitionId) {
        ReportDefinition definition = reportMapper.getDefinition(workspaceId, reportDefinitionId);
        if (definition == null) {
            throw new ResourceNotFoundException("Report not found with id: " + reportDefinitionId);
        }
        return definition;
    }

    private ReportSchedule requireSchedule(int workspaceId, int reportDefinitionId) {
        ReportSchedule schedule = scheduleMapper.getByReport(workspaceId, reportDefinitionId);
        if (schedule == null) {
            throw new ResourceNotFoundException("Report delivery schedule not found");
        }
        return schedule;
    }

    private List<User> activeMembers(int workspaceId) {
        return workspaceService.getMembers(workspaceId);
    }

    private String serializeRecipientIds(List<Integer> recipientIds) {
        try {
            return objectMapper.writeValueAsString(recipientIds);
        } catch (JacksonException exception) {
            throw new BadRequestException("Invalid report recipients");
        }
    }

    private List<Integer> readRecipientIds(String json) {
        try {
            Integer[] values = objectMapper.readValue(json, Integer[].class);
            if (values == null) {
                throw new IllegalArgumentException("Missing report recipient ids");
            }
            if (Arrays.stream(values).anyMatch(value -> value == null || value <= 0)) {
                throw new IllegalArgumentException("Invalid report recipient id");
            }
            return List.copyOf(Arrays.asList(values));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BadRequestException("Corrupt report recipient configuration");
        }
    }

    private static boolean isClaimable(
            ReportSchedule schedule,
            int expectedRunAsUserId,
            LocalDateTime now) {
        return schedule != null
                && schedule.isEnabled()
                && schedule.getRunAsUserId() == expectedRunAsUserId
                && schedule.getNextRunAt() != null
                && !schedule.getNextRunAt().isAfter(now);
    }

    private static String memberLabel(User member) {
        if (member.getDisplayName() != null && !member.getDisplayName().isBlank()) {
            return member.getDisplayName();
        }
        return member.getUsername();
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static DuplicateResourceException duplicateSchedule() {
        return new DuplicateResourceException(
                "schedule", "This report already has a delivery schedule");
    }

    /** Validated delivery identity for one scheduler occurrence. */
    public record DeliveryAccess(User user, String role, String denialReason) {
        static DeliveryAccess allowed(User user, String role) {
            return new DeliveryAccess(user, role, null);
        }

        static DeliveryAccess denied(String reason) {
            return new DeliveryAccess(null, null, reason);
        }

        /** Whether an active member with all required permissions was resolved. */
        public boolean allowed() {
            return user != null && role != null && denialReason == null;
        }
    }

    private record ControlAccess(User user, String role, Set<Permission> permissions) {
    }

    private record ValidatedSchedule(
            String cadence,
            String recipientUserIds,
            String timezone,
            int hourOfDay,
            boolean enabled,
            LocalDateTime nextRunAt) {
    }
}
