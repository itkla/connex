package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.dto.WorkflowEnrollment;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

@ExtendWith(MockitoExtension.class)
class WorkflowEnrollmentPolicyServiceTest {

    @Mock private WorkflowRunMapper runMapper;
    @Mock private WorkflowRecordPolicyService recordPolicyService;
    @Mock private WorkflowRecordGuard recordGuard;
    @Mock private SegmentService segmentService;
    @Mock private CompiledWorkflow compiled;

    private WorkflowEnrollmentPolicyService service;
    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        service = new WorkflowEnrollmentPolicyService(
            runMapper, recordPolicyService, recordGuard, segmentService);
        run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setWorkflowVersionId(29L);
        run.setRecordType("deal");
        run.setRecordId(19);
        lenient().when(recordPolicyService.entryMatched(run, compiled, 17)).thenReturn(true);
        lenient().when(runMapper.currentTimestamp(7, 11))
            .thenReturn(LocalDateTime.of(2027, 1, 10, 12, 0));
    }

    @Test
    void rejectsAnActiveRunForTheSameWorkflowAndRecord() {
        when(compiled.enrollment()).thenReturn(new WorkflowEnrollment(null, true, 0));
        when(runMapper.hasActiveEnrollment(7, 11, "deal", 19)).thenReturn(true);

        var decision = service.evaluateLocked(run, compiled, 17);

        assertEquals("active_run_exists", decision.reason());
        assertNull(decision.eligibleAt());
    }

    @Test
    void computesCooldownAcrossPublishedVersions() {
        when(compiled.enrollment()).thenReturn(new WorkflowEnrollment(null, false, 120));
        when(runMapper.latestEnrollmentStartedAt(7, 11, "deal", 19))
            .thenReturn(LocalDateTime.of(2027, 1, 10, 11, 0));

        var decision = service.evaluateLocked(run, compiled, 17);

        assertEquals("cooldown_active", decision.reason());
        assertEquals(LocalDateTime.of(2027, 1, 10, 13, 0), decision.eligibleAt());
        verify(runMapper).latestEnrollmentStartedAt(7, 11, "deal", 19);
    }

    @Test
    void preservesTheEntryConditionFailureBeforeRepeatChecks() {
        when(recordPolicyService.entryMatched(run, compiled, 17)).thenReturn(false);

        var decision = service.evaluateLocked(run, compiled, 17);

        assertEquals("entry_condition_not_matched", decision.reason());
        assertNull(decision.eligibleAt());
    }
}
