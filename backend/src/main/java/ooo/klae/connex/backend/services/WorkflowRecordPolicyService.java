package ooo.klae.connex.backend.services;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

/** Locks and evaluates schema-v2 entry and stop policy against the current primary record. */
@Service
@RequiredArgsConstructor
public class WorkflowRecordPolicyService {

    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final WorkflowRecordGuard recordGuard;
    private final SegmentService segmentService;

    public String stopReason(
            WorkflowRun run, CompiledWorkflow compiled, int attributionUserId) {
        Object record = lockPrimary(run);
        if ("date".equals(run.getTriggerType())
                && (!"deal".equals(run.getRecordType())
                    || !(record instanceof Deal deal)
                    || !sameDate(run.getDateSourceDate(), deal.getExpectedCloseDate()))) {
            return "date_superseded";
        }
        SegmentDefinition stopConditions = compiled.stopConditions();
        return stopConditions != null && segmentService.matchesEntity(
                run.getWorkspaceId(), attributionUserId, run.getRecordType(),
                stopConditions, run.getRecordId())
            ? "condition_matched" : null;
    }

    public boolean entryMatched(
            WorkflowRun run, CompiledWorkflow compiled, int attributionUserId) {
        lockPrimary(run);
        SegmentDefinition condition = compiled.enrollment() == null
            ? null : compiled.enrollment().condition();
        return condition == null || segmentService.matchesEntity(
            run.getWorkspaceId(), attributionUserId, run.getRecordType(),
            condition, run.getRecordId());
    }

    private Object lockPrimary(WorkflowRun run) {
        Object record = switch (run.getRecordType()) {
            case "person" -> personMapper.getVisiblePersonByIdForUpdate(
                run.getWorkspaceId(), run.getRecordId());
            case "company" -> companyMapper.getVisibleCompanyByIdForUpdate(
                run.getWorkspaceId(), run.getRecordId());
            case "deal" -> dealMapper.getDealByIdForUpdate(
                run.getWorkspaceId(), run.getRecordId());
            default -> null;
        };
        if (record == null) {
            throw new WorkflowExecutionException(
                "record_unavailable",
                "The workflow record is no longer available for automation.",
                true);
        }
        recordGuard.requireAccessible(run);
        return record;
    }

    private static boolean sameDate(LocalDate sourceDate, String currentDate) {
        return sourceDate != null
            && currentDate != null
            && sourceDate.toString().equals(currentDate);
    }
}
