package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DocumentTemplateMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ProductMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.AttachmentDto;
import ooo.klae.connex.backend.dto.CampaignSummaryDto;
import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.DocumentTemplateSummaryDto;
import ooo.klae.connex.backend.dto.GeneratedDocumentSummaryDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.PipelineDto;
import ooo.klae.connex.backend.dto.ProductSummaryDto;
import ooo.klae.connex.backend.dto.ReportSummaryDto;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.dto.WorkflowSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.util.LikePattern;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * Global search across every first-class object the sidebar presents.
 *
 * <p>Each group is executed with the same tenant predicate its own list endpoint uses, and gated
 * with the same permission that endpoint enforces: search must never disclose a row the caller
 * could not already read through the object's own surface. A gated group the caller may not read is
 * served empty rather than refused, so one unreadable group cannot fail the whole query.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int RESULT_LIMIT = 10;
    private static final int CANDIDATE_BATCH_SIZE = 25;
    private static final int MAX_CANDIDATES = 250;
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final CompanyMapper companyMapper;
    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final PipelineMapper pipelineMapper;
    private final TagMapper tagMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    private final AttachmentMapper attachmentMapper;
    private final ProductMapper productMapper;
    private final CampaignMapper campaignMapper;
    private final ReportMapper reportMapper;
    private final DocumentTemplateMapper documentTemplateMapper;
    private final DealDocumentMapper dealDocumentMapper;
    private final WorkflowMapper workflowMapper;
    private final WorkspaceService workspaceService;
    private final ReferenceService referenceService;

    /**
     * Runs one bounded global search in the active workspace.
     *
     * @param query the raw caller query
     * @return the grouped results, with unreadable groups empty
     */
    public SearchResultsDto search(String query) {
        if (query == null || query.isBlank()) {
            return empty();
        }

        String trimmed = query.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("Search query is too long (max " + MAX_QUERY_LENGTH + " characters)");
        }

        String pattern = LikePattern.containing(trimmed);
        String needle = foldSearchText(trimmed);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        Set<Permission> permissions = workspaceService.permissionsFor(workspaceId, userId);
        List<Activity> activities = searchVisibleActivities(workspaceId, pattern, needle);
        List<Note> notes = mergeVisibleNotes(
            searchVisibleNotes(workspaceId, pattern, needle, userId),
            searchVisibleNotesByAuthor(workspaceId, pattern, userId));
        List<Task> tasks = searchVisibleTasks(workspaceId, pattern, needle);
        return new SearchResultsDto(
            companyMapper.search(workspaceId, pattern).stream().map(CompanyDto::from).toList(),
            personMapper.search(workspaceId, pattern).stream().map(PersonDto::from).toList(),
            referenceService.hydrateDeals(workspaceId, dealMapper.search(workspaceId, pattern))
                .stream().map(DealDto::from).toList(),
            pipelineMapper.search(workspaceId, pattern).stream().map(PipelineDto::from).toList(),
            tagMapper.search(workspaceId, pattern).stream().map(TagDto::from).toList(),
            activities.stream().map(ActivityDto::from).toList(),
            notes.stream().map(NoteDto::from).toList(),
            tasks.stream().map(TaskDto::from).toList(),
            userMapper.search(workspaceId, pattern).stream().map(UserDto::from).toList(),
            attachmentMapper.search(workspaceId, pattern).stream().map(AttachmentDto::from).toList(),
            productMapper.search(workspaceId, pattern),
            gated(permissions, Permission.CAMPAIGN_VIEW,
                () -> campaignMapper.searchCampaigns(workspaceId, pattern)),
            gated(permissions, Permission.REPORT_READ,
                () -> reportMapper.searchDefinitions(workspaceId, pattern)),
            documentTemplateMapper.search(workspaceId, pattern),
            dealDocumentMapper.search(workspaceId, pattern),
            gated(permissions, Permission.RULE_MANAGE,
                () -> workflowMapper.search(workspaceId, pattern))
        );
    }

    private List<Activity> searchVisibleActivities(int workspaceId, String pattern, String needle) {
        List<Activity> visible = new ArrayList<>();
        int offset = 0;
        while (visible.size() < RESULT_LIMIT && offset < MAX_CANDIDATES) {
            int limit = Math.min(CANDIDATE_BATCH_SIZE, MAX_CANDIDATES - offset);
            List<Activity> batch = activityMapper.search(workspaceId, pattern, limit, offset);
            if (batch.isEmpty()) {
                return visible;
            }
            visible.addAll(referenceService.hydrateActivities(workspaceId, batch).stream()
                .filter(activity -> visibleTextMatches(needle, activity.getSubject(), activity.getNotes()))
                .limit(RESULT_LIMIT - visible.size())
                .toList());
            offset += batch.size();
            if (batch.size() < limit) {
                return visible;
            }
        }
        return visible;
    }

    private List<Note> searchVisibleNotes(
            int workspaceId, String pattern, String needle, int currentUserId) {
        List<Note> visible = new ArrayList<>();
        int offset = 0;
        while (visible.size() < RESULT_LIMIT && offset < MAX_CANDIDATES) {
            int limit = Math.min(CANDIDATE_BATCH_SIZE, MAX_CANDIDATES - offset);
            List<Note> batch = noteMapper.searchVisible(
                workspaceId, pattern, currentUserId, limit, offset);
            if (batch.isEmpty()) {
                return visible;
            }
            visible.addAll(referenceService.hydrate(workspaceId, batch).stream()
                .filter(note -> visibleNoteMatches(needle, note))
                .limit(RESULT_LIMIT - visible.size())
                .toList());
            offset += batch.size();
            if (batch.size() < limit) {
                return visible;
            }
        }
        return visible;
    }

    private List<Note> searchVisibleNotesByAuthor(
            int workspaceId, String pattern, int currentUserId) {
        List<Note> visible = new ArrayList<>();
        int offset = 0;
        while (visible.size() < RESULT_LIMIT && offset < MAX_CANDIDATES) {
            int limit = Math.min(CANDIDATE_BATCH_SIZE, MAX_CANDIDATES - offset);
            List<Note> batch = noteMapper.getVisibleNotesPage(
                workspaceId, currentUserId, null, List.of(), "created", "desc", limit, offset);
            if (batch.isEmpty()) {
                return visible;
            }
            List<Integer> authorIds = batch.stream()
                .map(Note::getAuthor)
                .filter(author -> author != null)
                .map(author -> author.getId())
                .distinct()
                .toList();
            Set<Integer> matchingAuthorIds = authorIds.isEmpty()
                ? Set.of()
                : Set.copyOf(userMapper.findMatchingWorkspaceMemberIdsIn(
                    workspaceId, pattern, authorIds));
            List<Note> matches = batch.stream()
                .filter(note -> note.getAuthor() != null
                    && matchingAuthorIds.contains(note.getAuthor().getId()))
                .limit(RESULT_LIMIT - visible.size())
                .toList();
            visible.addAll(referenceService.hydrate(workspaceId, matches));
            offset += batch.size();
            if (batch.size() < limit) {
                return visible;
            }
        }
        return visible;
    }

    private List<Task> searchVisibleTasks(int workspaceId, String pattern, String needle) {
        List<Task> visible = new ArrayList<>();
        int offset = 0;
        while (visible.size() < RESULT_LIMIT && offset < MAX_CANDIDATES) {
            int limit = Math.min(CANDIDATE_BATCH_SIZE, MAX_CANDIDATES - offset);
            List<Task> batch = taskMapper.search(workspaceId, pattern, limit, offset);
            if (batch.isEmpty()) {
                return visible;
            }
            visible.addAll(referenceService.hydrateTasks(workspaceId, batch).stream()
                .filter(task -> visibleTextMatches(needle, task.getDescription()))
                .limit(RESULT_LIMIT - visible.size())
                .toList());
            offset += batch.size();
            if (batch.size() < limit) {
                return visible;
            }
        }
        return visible;
    }

    private static boolean visibleNoteMatches(String needle, Note note) {
        return visibleTextMatches(needle,
            note.getTitle(),
            note.getContent(),
            note.getPerson() == null ? null : note.getPerson().getName(),
            note.getDeal() == null ? null : note.getDeal().getName());
    }

    private static List<Note> mergeVisibleNotes(
            List<Note> contentMatches, List<Note> authorMatches) {
        Map<Integer, Note> unique = new LinkedHashMap<>();
        contentMatches.forEach(note -> unique.put(note.getId(), note));
        authorMatches.forEach(note -> unique.putIfAbsent(note.getId(), note));
        return unique.values().stream()
            .sorted(Comparator.comparing(Note::getCreatedAt).reversed()
                .thenComparing(Comparator.comparingInt(Note::getId).reversed()))
            .limit(RESULT_LIMIT)
            .toList();
    }

    private static boolean visibleTextMatches(String needle, String... values) {
        for (String value : values) {
            if (value != null && foldSearchText(value).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String foldSearchText(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD);
        return COMBINING_MARKS.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * Runs a group's query only when the caller holds the permission its own read endpoint
     * enforces.
     *
     * <p>Products, document templates, and generated documents have no permission gate here on
     * purpose: {@code GET /api/products}, {@code GET /api/document-templates}, and
     * {@code GET /api/deals/{id}/documents} are all membership-only reads today, so gating their
     * search groups would hide rows the caller can open from the sidebar. If one of those surfaces
     * ever acquires a permission, add it here in the same shape.
     *
     * @param <T> the group's row type
     * @param permissions the caller's effective permissions
     * @param required the permission the group's own read endpoint enforces
     * @param group the group query
     * @return the group's rows, or an empty list when the caller lacks the permission
     */
    private static <T> List<T> gated(
            Set<Permission> permissions, Permission required, Supplier<List<T>> group) {
        return permissions.contains(required) ? group.get() : List.of();
    }

    private static SearchResultsDto empty() {
        return new SearchResultsDto(
            List.<CompanyDto>of(), List.<PersonDto>of(), List.<DealDto>of(), List.<PipelineDto>of(),
            List.<TagDto>of(), List.<ActivityDto>of(), List.<NoteDto>of(), List.<TaskDto>of(),
            List.<UserDto>of(), List.<AttachmentDto>of(), List.<ProductSummaryDto>of(),
            List.<CampaignSummaryDto>of(), List.<ReportSummaryDto>of(),
            List.<DocumentTemplateSummaryDto>of(), List.<GeneratedDocumentSummaryDto>of(),
            List.<WorkflowSummaryDto>of());
    }
}
