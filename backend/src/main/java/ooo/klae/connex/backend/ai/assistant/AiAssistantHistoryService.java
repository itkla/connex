package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.ReferenceService;
import ooo.klae.connex.backend.services.WorkspaceService;

/** Loads bounded assistant history candidates before the caller applies its egress policy. */
@Service
@RequiredArgsConstructor
public class AiAssistantHistoryService {
    private static final int MAX_LIMIT = 20;

    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    private final WorkspaceService workspaceService;
    private final ReferenceService referenceService;

    /**
     * One record's activity, bounded to a period when the turn declared one.
     *
     * <p>The bounds reach the query rather than filtering its rows afterwards: a trailing read that
     * returned the newest rows and then discarded the out-of-period ones would hand back fewer rows
     * than the caller asked for while in-period rows it never looked at still existed.
     *
     * @param startUtc inclusive lower bound on the stored timestamp, or null when unbounded
     * @param endUtc inclusive upper bound on the stored timestamp, or null when unbounded
     */
    List<Activity> activitiesForPerson(
            int personId, LocalDateTime startUtc, LocalDateTime endUtc, int limit) {
        requireLimit(limit);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return referenceService.hydrateActivities(
                workspaceId,
                activityMapper.getAiAssistantActivitiesByPersonId(
                        workspaceId, personId, organizationWorkspaceIds,
                        startUtc, endUtc, limit));
    }

    /** One deal's activity, bounded to a period when the turn declared one. */
    List<Activity> activitiesForDeal(
            int dealId, LocalDateTime startUtc, LocalDateTime endUtc, int limit) {
        requireLimit(limit);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return referenceService.hydrateActivities(
                workspaceId,
                activityMapper.getAiAssistantActivitiesByDealId(
                        workspaceId, dealId, organizationWorkspaceIds,
                        startUtc, endUtc, limit));
    }

    /** One company's activity, bounded to a period when the turn declared one. */
    List<Activity> activitiesForCompany(
            int companyId, LocalDateTime startUtc, LocalDateTime endUtc, int limit) {
        requireLimit(limit);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return referenceService.hydrateActivities(
                workspaceId,
                activityMapper.getAiAssistantActivitiesByCompanyId(
                        workspaceId, companyId, organizationWorkspaceIds,
                        startUtc, endUtc, limit));
    }

    List<Task> tasksForPerson(int personId, int limit) {
        requireLimit(limit);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return referenceService.hydrateTasks(
                workspaceId,
                taskMapper.getAiAssistantTasksByPersonId(
                        workspaceId, personId, organizationWorkspaceIds, limit));
    }

    List<Task> tasksForDeal(int dealId, int limit) {
        requireLimit(limit);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return referenceService.hydrateTasks(
                workspaceId,
                taskMapper.getAiAssistantTasksByDealId(
                        workspaceId, dealId, organizationWorkspaceIds, limit));
    }

    List<Task> tasksForCompany(int companyId, int limit) {
        requireLimit(limit);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return referenceService.hydrateTasks(
                workspaceId,
                taskMapper.getAiAssistantTasksByCompanyId(
                        workspaceId, companyId, organizationWorkspaceIds, limit));
    }

    List<Note> notesForCompany(int companyId, int limit) {
        requireLimit(limit);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return referenceService.hydrate(
                workspaceId,
                noteMapper.getAiAssistantVisibleNotesByCompanyId(
                        workspaceId,
                        companyId,
                        currentUserId,
                        organizationWorkspaceIds,
                        limit));
    }

    private List<Integer> currentOrganizationWorkspaceIds(int workspaceId) {
        return workspaceScopeControlAccess.getForWorkspace(workspaceId).workspaceIds();
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Assistant history limit must be between 1 and 20");
        }
    }
}
