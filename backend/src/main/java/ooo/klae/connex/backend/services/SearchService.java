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

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
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
    private final AuditService auditService;
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
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        Set<Permission> permissions = workspaceService.permissionsFor(workspaceId, userId);
        auditService.record("search", "search", null, query, "Search performed", null);
        return new SearchResultsDto(
            companyMapper.search(workspaceId, pattern).stream().map(CompanyDto::from).toList(),
            personMapper.search(workspaceId, pattern).stream().map(PersonDto::from).toList(),
            referenceService.hydrateDeals(workspaceId, dealMapper.search(workspaceId, pattern))
                .stream().map(DealDto::from).toList(),
            pipelineMapper.search(workspaceId, pattern).stream().map(PipelineDto::from).toList(),
            tagMapper.search(workspaceId, pattern).stream().map(TagDto::from).toList(),
            referenceService.hydrateActivities(workspaceId, activityMapper.search(workspaceId, pattern)).stream().map(ActivityDto::from).toList(),
            referenceService.hydrate(workspaceId, noteMapper.searchVisible(workspaceId, pattern, userId)).stream().map(NoteDto::from).toList(),
            referenceService.hydrateTasks(workspaceId, taskMapper.search(workspaceId, pattern)).stream().map(TaskDto::from).toList(),
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
