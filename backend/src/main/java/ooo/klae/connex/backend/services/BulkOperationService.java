package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.BulkOperationResult;
import ooo.klae.connex.backend.dto.RowError;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Applies one mutation across many records in a single request, reporting per-record success and
 * failure instead of failing the whole batch.
 *
 * <p>Each method is permission-gated at the boundary (a caller wholly lacking the permission gets a
 * 403, not a "0 succeeded" result) and resolves the active workspace once. Per record it first
 * confirms the id is visible in that workspace — so a foreign-tenant or stale id resolves to a
 * not-found {@link RowError} and is never mutated — then delegates to the matching single-record
 * service method. Because this service is not transactional and the delegate calls cross the bean
 * proxy, each single-record mutation runs in its own transaction: one record's failure rolls back
 * only that record and never blocks the others, and all of the delegate's existing behaviour
 * (audit, triggers, notifications, owner/stage reconciliation) is reused unchanged.
 *
 * <p>Shared inputs (the tag, owner, or stage a whole batch targets) are validated once up front and
 * fail the request with the usual status, rather than producing an identical error on every row.
 */
@Service
@RequiredArgsConstructor
public class BulkOperationService {

    private final WorkspaceService workspaceService;
    private final PersonService personService;
    private final CompanyService companyService;
    private final DealService dealService;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final TagMapper tagMapper;
    private final PipelineMapper pipelineMapper;

    @RequirePermission(Permission.PERSON_UPDATE)
    public BulkOperationResult addTagToPersons(List<Integer> ids, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTag(workspaceId, tagId);
        return apply(ids, id -> personMapper.exists(workspaceId, id), id -> personService.addTag(id, tagId));
    }

    @RequirePermission(Permission.PERSON_UPDATE)
    public BulkOperationResult removeTagFromPersons(List<Integer> ids, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTag(workspaceId, tagId);
        return apply(ids, id -> personMapper.exists(workspaceId, id), id -> personService.removeTag(id, tagId));
    }

    @RequirePermission(Permission.PERSON_DELETE)
    public BulkOperationResult deletePersons(List<Integer> ids) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return apply(ids, id -> personMapper.exists(workspaceId, id), personService::delete);
    }

    @RequirePermission(Permission.COMPANY_UPDATE)
    public BulkOperationResult addTagToCompanies(List<Integer> ids, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTag(workspaceId, tagId);
        return apply(ids, id -> companyMapper.exists(workspaceId, id), id -> companyService.addTag(id, tagId));
    }

    @RequirePermission(Permission.COMPANY_UPDATE)
    public BulkOperationResult removeTagFromCompanies(List<Integer> ids, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTag(workspaceId, tagId);
        return apply(ids, id -> companyMapper.exists(workspaceId, id), id -> companyService.removeTag(id, tagId));
    }

    @RequirePermission(Permission.COMPANY_DELETE)
    public BulkOperationResult deleteCompanies(List<Integer> ids) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return apply(ids, id -> companyMapper.exists(workspaceId, id), companyService::deleteCompany);
    }

    @RequirePermission(Permission.DEAL_UPDATE)
    public BulkOperationResult addTagToDeals(List<Integer> ids, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTag(workspaceId, tagId);
        return apply(ids, id -> dealMapper.exists(workspaceId, id), id -> dealService.addTag(id, tagId));
    }

    @RequirePermission(Permission.DEAL_UPDATE)
    public BulkOperationResult removeTagFromDeals(List<Integer> ids, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTag(workspaceId, tagId);
        return apply(ids, id -> dealMapper.exists(workspaceId, id), id -> dealService.removeTag(id, tagId));
    }

    @RequirePermission(Permission.DEAL_DELETE)
    public BulkOperationResult deleteDeals(List<Integer> ids) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return apply(ids, id -> dealMapper.exists(workspaceId, id), dealService::delete);
    }

    @RequirePermission(Permission.DEAL_UPDATE)
    public BulkOperationResult assignOwnerToDeals(List<Integer> ids, Integer ownerId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (ownerId != null) workspaceService.requireMember(workspaceId, ownerId);
        return apply(ids, id -> dealMapper.exists(workspaceId, id), id -> dealService.updateOwner(id, ownerId));
    }

    @RequirePermission(Permission.DEAL_UPDATE)
    public BulkOperationResult changeStageForDeals(List<Integer> ids, int stageId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (pipelineMapper.getStageById(workspaceId, stageId) == null) {
            throw new ResourceNotFoundException("Stage not found with id: " + stageId);
        }
        return apply(ids, id -> dealMapper.exists(workspaceId, id), id -> dealService.changeStage(id, stageId));
    }

    private void requireTag(int workspaceId, int tagId) {
        if (tagMapper.getTagById(workspaceId, tagId) == null) {
            throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        }
    }

    /**
     * De-duplicates the ids, then for each: skips with a not-found error when it is not visible in
     * the active workspace, otherwise runs the action and records any failure against that row.
     * {@code rowIndex} is the id's position in the de-duplicated request list.
     */
    private BulkOperationResult apply(List<Integer> ids, IntPredicate visibleInWorkspace, IntConsumer action) {
        List<Integer> distinct = ids.stream().distinct().toList();
        List<RowError> errors = new ArrayList<>();
        int succeeded = 0;
        for (int i = 0; i < distinct.size(); i++) {
            int id = distinct.get(i);
            try {
                if (!visibleInWorkspace.test(id)) {
                    errors.add(new RowError(i, "Record not found in workspace"));
                    continue;
                }
                action.accept(id);
                succeeded++;
            } catch (RuntimeException exception) {
                errors.add(new RowError(i, reason(exception)));
            }
        }
        return new BulkOperationResult(succeeded, errors.size(), errors);
    }

    /** Surfaces domain-exception messages, which are safe and actionable, and hides everything else. */
    private static String reason(RuntimeException exception) {
        if (exception instanceof ResourceNotFoundException || exception instanceof BadRequestException) {
            return exception.getMessage();
        }
        return "Operation failed";
    }
}
