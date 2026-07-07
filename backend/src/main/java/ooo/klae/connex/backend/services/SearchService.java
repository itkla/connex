package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.AttachmentDto;
import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.PipelineDto;
import ooo.klae.connex.backend.dto.SearchResultsDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.util.LikePattern;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int RESULT_LIMIT = 10;
    private static final int CANDIDATE_BATCH_SIZE = 25;

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
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final ReferenceService referenceService;

    public SearchResultsDto search(String query) {
        if (query == null || query.isBlank()) {
            return new SearchResultsDto(List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        String trimmed = query.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("Search query is too long (max " + MAX_QUERY_LENGTH + " characters)");
        }

        String pattern = LikePattern.containing(trimmed);
        String needle = foldSearchText(trimmed);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int currentUserId = workspaceService.getCurrentUserId();
        List<Activity> activities = searchVisibleActivities(workspaceId, pattern, needle);
        List<Note> notes = searchVisibleNotes(workspaceId, pattern, needle, currentUserId);
        List<Task> tasks = searchVisibleTasks(workspaceId, pattern, needle);
        auditService.record("search", "search", null, query, "Search performed", null);
        return new SearchResultsDto(
            companyMapper.search(workspaceId, pattern).stream().map(CompanyDto::from).toList(),
            personMapper.search(workspaceId, pattern).stream().map(PersonDto::from).toList(),
            dealMapper.search(workspaceId, pattern).stream().map(DealDto::from).toList(),
            pipelineMapper.search(workspaceId, pattern).stream().map(PipelineDto::from).toList(),
            tagMapper.search(workspaceId, pattern).stream().map(TagDto::from).toList(),
            activities.stream().map(ActivityDto::from).toList(),
            notes.stream().map(NoteDto::from).toList(),
            tasks.stream().map(TaskDto::from).toList(),
            userMapper.search(workspaceId, pattern).stream().map(UserDto::from).toList(),
            attachmentMapper.search(workspaceId, pattern).stream().map(AttachmentDto::from).toList()
        );
    }

    private List<Activity> searchVisibleActivities(int workspaceId, String pattern, String needle) {
        List<Activity> visible = new ArrayList<>();
        int offset = 0;
        while (visible.size() < RESULT_LIMIT) {
            List<Activity> batch = activityMapper.search(workspaceId, pattern, CANDIDATE_BATCH_SIZE, offset);
            if (batch.isEmpty()) {
                return visible;
            }
            visible.addAll(referenceService.hydrateActivities(workspaceId, batch).stream()
                .filter(activity -> visibleTextMatches(needle, activity.getSubject(), activity.getNotes()))
                .limit(RESULT_LIMIT - visible.size())
                .toList());
            offset += batch.size();
            if (batch.size() < CANDIDATE_BATCH_SIZE) {
                return visible;
            }
        }
        return visible;
    }

    private List<Note> searchVisibleNotes(int workspaceId, String pattern, String needle, int currentUserId) {
        List<Note> visible = new ArrayList<>();
        int offset = 0;
        while (visible.size() < RESULT_LIMIT) {
            List<Note> batch = noteMapper.searchVisible(workspaceId, pattern, currentUserId, CANDIDATE_BATCH_SIZE, offset);
            if (batch.isEmpty()) {
                return visible;
            }
            visible.addAll(referenceService.hydrate(workspaceId, batch).stream()
                .filter(note -> visibleNoteMatches(needle, note))
                .limit(RESULT_LIMIT - visible.size())
                .toList());
            offset += batch.size();
            if (batch.size() < CANDIDATE_BATCH_SIZE) {
                return visible;
            }
        }
        return visible;
    }

    private List<Task> searchVisibleTasks(int workspaceId, String pattern, String needle) {
        List<Task> visible = new ArrayList<>();
        int offset = 0;
        while (visible.size() < RESULT_LIMIT) {
            List<Task> batch = taskMapper.search(workspaceId, pattern, CANDIDATE_BATCH_SIZE, offset);
            if (batch.isEmpty()) {
                return visible;
            }
            visible.addAll(referenceService.hydrateTasks(workspaceId, batch).stream()
                .filter(task -> visibleTextMatches(needle, task.getDescription()))
                .limit(RESULT_LIMIT - visible.size())
                .toList());
            offset += batch.size();
            if (batch.size() < CANDIDATE_BATCH_SIZE) {
                return visible;
            }
        }
        return visible;
    }

    private static boolean visibleNoteMatches(String needle, Note note) {
        return visibleTextMatches(needle,
            note.getTitle(),
            note.getContent(),
            note.getAuthor() == null ? null : note.getAuthor().getDisplayName(),
            note.getAuthor() == null ? null : note.getAuthor().getUsername(),
            note.getPerson() == null ? null : note.getPerson().getName(),
            note.getDeal() == null ? null : note.getDeal().getName());
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
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
