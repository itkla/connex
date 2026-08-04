package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.mappers.SegmentMapper;

/** Fails closed when a run's primary record is missing, archived, restricted, or foreign. */
@Service
@RequiredArgsConstructor
public class WorkflowRecordGuard {

    private final SegmentMapper segmentMapper;

    public void requireAccessible(WorkflowRun run) {
        requireAccessible(
            run.getWorkspaceId(), run.getRecordType(), run.getRecordId());
    }

    /** Requires one record to remain in the canonical runtime's workspace/restriction universe. */
    public void requireAccessible(
            int workspaceId, String recordType, int recordId) {
        if (!segmentMapper.entityIdInWorkspace(
                workspaceId, recordType, recordId)) {
            throw new WorkflowExecutionException(
                "record_unavailable",
                "The workflow record is no longer available for automation.",
                true);
        }
    }
}
