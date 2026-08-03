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
        if (!segmentMapper.entityIdInWorkspace(
                run.getWorkspaceId(), run.getRecordType(), run.getRecordId())) {
            throw new WorkflowExecutionException(
                "record_unavailable",
                "The workflow record is no longer available for automation.",
                true);
        }
    }
}
