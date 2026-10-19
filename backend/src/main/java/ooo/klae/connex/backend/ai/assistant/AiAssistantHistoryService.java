package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.mission.AiAgentScopePolicy;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
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
        AiAgentScopePolicy mission = missionScope("person", personId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return activities(
                workspaceId, mission,
                activityMapper.getAiAssistantActivitiesByPersonId(
                        workspaceId, personId, organizationWorkspaceIds,
                        startUtc, endUtc, limit));
    }

    /** One deal's activity, bounded to a period when the turn declared one. */
    List<Activity> activitiesForDeal(
            int dealId, LocalDateTime startUtc, LocalDateTime endUtc, int limit) {
        requireLimit(limit);
        AiAgentScopePolicy mission = missionScope("deal", dealId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return activities(
                workspaceId, mission,
                activityMapper.getAiAssistantActivitiesByDealId(
                        workspaceId, dealId, organizationWorkspaceIds,
                        startUtc, endUtc, limit));
    }

    /** One company's activity, bounded to a period when the turn declared one. */
    List<Activity> activitiesForCompany(
            int companyId, LocalDateTime startUtc, LocalDateTime endUtc, int limit) {
        requireLimit(limit);
        AiAgentScopePolicy mission = missionScope("company", companyId);
        if (mission != null) {
            return List.of();
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return activities(
                workspaceId, mission,
                activityMapper.getAiAssistantActivitiesByCompanyId(
                        workspaceId, companyId, organizationWorkspaceIds,
                        startUtc, endUtc, limit));
    }

    List<Task> tasksForPerson(int personId, int limit) {
        requireLimit(limit);
        AiAgentScopePolicy mission = missionScope("person", personId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return tasks(
                workspaceId, mission,
                taskMapper.getAiAssistantTasksByPersonId(
                        workspaceId, personId, organizationWorkspaceIds, limit));
    }

    List<Task> tasksForDeal(int dealId, int limit) {
        requireLimit(limit);
        AiAgentScopePolicy mission = missionScope("deal", dealId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return tasks(
                workspaceId, mission,
                taskMapper.getAiAssistantTasksByDealId(
                        workspaceId, dealId, organizationWorkspaceIds, limit));
    }

    List<Task> tasksForCompany(int companyId, int limit) {
        requireLimit(limit);
        AiAgentScopePolicy mission = missionScope("company", companyId);
        if (mission != null) {
            return List.of();
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> organizationWorkspaceIds = currentOrganizationWorkspaceIds(workspaceId);
        return tasks(
                workspaceId, mission,
                taskMapper.getAiAssistantTasksByCompanyId(
                        workspaceId, companyId, organizationWorkspaceIds, limit));
    }

    /** Loads full reader-authorized note prose before assistant screening or truncation. */
    List<Note> notesForPerson(int personId, int limit, boolean includePrivateNotes) {
        requireLimit(limit);
        AiAgentScopePolicy mission = missionScope("person", personId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return mission == null
                ? referenceService.hydrate(workspaceId, noteMapper.getAiAssistantVisibleNotesByPersonId(
                        workspaceId, personId, workspaceService.getCurrentUserId(),
                        currentOrganizationWorkspaceIds(workspaceId), limit, includePrivateNotes))
                : missionNotes(mission, noteMapper.getAiAssistantVisibleNotesByPersonId(
                        workspaceId, personId, workspaceService.getCurrentUserId(),
                        currentOrganizationWorkspaceIds(workspaceId), limit, includePrivateNotes));
    }

    /** Loads full reader-authorized deal note prose before assistant screening or truncation. */
    List<Note> notesForDeal(int dealId, int limit, boolean includePrivateNotes) {
        requireLimit(limit);
        AiAgentScopePolicy mission = missionScope("deal", dealId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return mission == null
                ? referenceService.hydrate(workspaceId, noteMapper.getAiAssistantVisibleNotesByDealId(
                        workspaceId, dealId, workspaceService.getCurrentUserId(),
                        currentOrganizationWorkspaceIds(workspaceId), limit, includePrivateNotes))
                : missionNotes(mission, noteMapper.getAiAssistantVisibleNotesByDealId(
                        workspaceId, dealId, workspaceService.getCurrentUserId(),
                        currentOrganizationWorkspaceIds(workspaceId), limit, includePrivateNotes));
    }

    List<Note> notesForCompany(int companyId, int limit, boolean includePrivateNotes) {
        requireLimit(limit);
        AiAgentScopePolicy mission = missionScope("company", companyId);
        if (mission != null) {
            return List.of();
        }
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
                        limit, includePrivateNotes));
    }

    /** Refuses before the bounded child query; company evidence requires an explicit future expansion. */
    private static AiAgentScopePolicy missionScope(String kind, int id) {
        AiAgentScopePolicy mission = AiAgentScopePolicy.current();
        if (mission != null) {
            mission.requireRecord(kind, id);
        }
        return mission;
    }

    private List<Activity> activities(int workspaceId, AiAgentScopePolicy mission, List<Activity> rows) {
        if (mission == null) {
            return referenceService.hydrateActivities(workspaceId, rows);
        }
        return rows.stream()
                .filter(row -> allowedParents(mission, row.getPerson(), row.getDeal()))
                .map(row -> {
                    Activity projected = new Activity();
                    projected.setId(row.getId());
                    projected.setWorkspaceId(row.getWorkspaceId());
                    projected.setType(redact(mission, row.getType()));
                    projected.setSubject(redact(mission, row.getSubject()));
                    projected.setNotes(redact(mission, row.getNotes()));
                    projected.setTimestamp(row.getTimestamp());
                    projected.setPerson(primaryPerson(mission, row.getPerson()));
                    projected.setDeal(primaryDeal(mission, row.getDeal()));
                    projected.setReferences(memberCandidates(row.getType(), row.getSubject(), row.getNotes()));
                    return projected;
                }).toList();
    }

    private List<Task> tasks(int workspaceId, AiAgentScopePolicy mission, List<Task> rows) {
        if (mission == null) {
            return referenceService.hydrateTasks(workspaceId, rows);
        }
        return rows.stream()
                .filter(row -> allowedParents(mission, row.getPerson(), row.getDeal()))
                .map(row -> {
                    Task projected = new Task();
                    projected.setId(row.getId());
                    projected.setWorkspaceId(row.getWorkspaceId());
                    projected.setDescription(redact(mission, row.getDescription()));
                    projected.setStatus(row.getStatus());
                    projected.setCompleted(row.isCompleted());
                    projected.setDueDate(row.getDueDate());
                    projected.setAssignedTo(row.getAssignedTo());
                    projected.setPerson(primaryPerson(mission, row.getPerson()));
                    projected.setDeal(primaryDeal(mission, row.getDeal()));
                    projected.setReferences(memberCandidates(row.getDescription()));
                    return projected;
                }).toList();
    }

    private static List<Note> missionNotes(AiAgentScopePolicy mission, List<Note> rows) {
        return rows.stream()
                .filter(row -> allowedParents(mission, row.getPerson(), row.getDeal()))
                .map(row -> {
                    Note projected = new Note();
                    projected.setId(row.getId());
                    projected.setWorkspaceId(row.getWorkspaceId());
                    projected.setTitle(redact(mission, row.getTitle()));
                    projected.setContent(redact(mission, row.getContent()));
                    projected.setVisibility(row.getVisibility());
                    projected.setCreatedAt(row.getCreatedAt());
                    projected.setUpdatedAt(row.getUpdatedAt());
                    projected.setPerson(primaryPerson(mission, row.getPerson()));
                    projected.setDeal(primaryDeal(mission, row.getDeal()));
                    projected.setReferences(memberCandidates(row.getTitle(), row.getContent()));
                    return projected;
                }).toList();
    }

    /**
     * Suppresses evidence about any secondary parent because reading its labels would widen scope,
     * while emitting its prose without those labels would weaken identifier masking.
     */
    private static boolean allowedParents(AiAgentScopePolicy mission, Person person, Deal deal) {
        return (person == null || ("person".equals(mission.kind()) && person.getId() == mission.id()))
                && (deal == null || ("deal".equals(mission.kind()) && deal.getId() == mission.id()));
    }

    private static Person primaryPerson(AiAgentScopePolicy mission, Person person) {
        if (!"person".equals(mission.kind()) || person == null || person.getId() != mission.id()) {
            return null;
        }
        Person primary = new Person();
        primary.setId(mission.id());
        return primary;
    }

    private static Deal primaryDeal(AiAgentScopePolicy mission, Deal deal) {
        if (!"deal".equals(mission.kind()) || deal == null || deal.getId() != mission.id()) {
            return null;
        }
        Deal primary = new Deal();
        primary.setId(mission.id());
        return primary;
    }

    private static String redact(AiAgentScopePolicy mission, String content) {
        return ReferenceService.redactMissionReferences(content, mission.kind(), mission.id());
    }

    /**
     * Carries authored member labels solely as masking candidates after linked prose is suppressed.
     * These transient references never grant visibility, allocate handles, or reach persistence.
     */
    private static List<EntityReference> memberCandidates(String... contents) {
        LinkedHashMap<String, EntityReference> labels = new LinkedHashMap<>();
        for (String content : contents) {
            if (content == null || content.isBlank()) {
                continue;
            }
            for (ReferenceService.MemberReference member : ReferenceService.memberReferenceReading(content).references()) {
                labels.computeIfAbsent(member.label(), label -> {
                    EntityReference candidate = new EntityReference();
                    candidate.setRefType("user");
                    candidate.setRefId(member.memberIds().stream().findFirst().orElse(0));
                    candidate.setLabel(label);
                    return candidate;
                });
            }
        }
        return List.copyOf(labels.values());
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
