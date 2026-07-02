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

import java.util.List;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int MAX_QUERY_LENGTH = 200;

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
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        auditService.record("search", "search", null, query, "Search performed", null);
        return new SearchResultsDto(
            companyMapper.search(workspaceId, pattern).stream().map(CompanyDto::from).toList(),
            personMapper.search(workspaceId, pattern).stream().map(PersonDto::from).toList(),
            dealMapper.search(workspaceId, pattern).stream().map(DealDto::from).toList(),
            pipelineMapper.search(workspaceId, pattern).stream().map(PipelineDto::from).toList(),
            tagMapper.search(workspaceId, pattern).stream().map(TagDto::from).toList(),
            activityMapper.search(workspaceId, pattern).stream().map(ActivityDto::from).toList(),
            referenceService.hydrate(workspaceId, noteMapper.search(workspaceId, pattern)).stream().map(NoteDto::from).toList(),
            referenceService.hydrateTasks(workspaceId, taskMapper.search(workspaceId, pattern)).stream().map(TaskDto::from).toList(),
            userMapper.search(workspaceId, pattern).stream().map(UserDto::from).toList(),
            attachmentMapper.search(workspaceId, pattern).stream().map(AttachmentDto::from).toList()
        );
    }
}
