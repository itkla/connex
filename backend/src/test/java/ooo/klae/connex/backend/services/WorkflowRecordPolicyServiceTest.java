package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

@ExtendWith(MockitoExtension.class)
class WorkflowRecordPolicyServiceTest {

    @Mock private PersonMapper personMapper;
    @Mock private CompanyMapper companyMapper;
    @Mock private DealMapper dealMapper;
    @Mock private WorkflowRecordGuard recordGuard;
    @Mock private SegmentService segmentService;
    @Mock private CompiledWorkflow compiled;

    private WorkflowRecordPolicyService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRecordPolicyService(
            personMapper, companyMapper, dealMapper, recordGuard, segmentService);
    }

    @Test
    void dateSourceChangeStopsBeforeAuthorConditions() {
        WorkflowRun run = run("deal", 19);
        run.setTriggerType("date");
        run.setDateSourceDate(java.time.LocalDate.of(2027, 3, 31));
        Deal deal = new Deal();
        deal.setExpectedCloseDate("2027-04-30");
        when(dealMapper.getDealByIdForUpdate(7, 19)).thenReturn(deal);

        String reason = service.stopReason(run, compiled, 17);

        assertEquals("date_superseded", reason);
        verify(recordGuard).requireAccessible(run);
        verify(segmentService, never()).matchesEntity(
            7, 17, "deal", null, 19);
    }

    @Test
    void lockedVisibilityGuardRejectsAContactSuspendedAfterDiscovery() {
        WorkflowRun run = run("person", 23);
        when(personMapper.getVisiblePersonByIdForUpdate(7, 23)).thenReturn(new Person());
        WorkflowExecutionException failure = new WorkflowExecutionException(
            "record_unavailable", "Unavailable", true);
        org.mockito.Mockito.doThrow(failure).when(recordGuard).requireAccessible(run);

        WorkflowExecutionException thrown = assertThrows(
            WorkflowExecutionException.class,
            () -> service.stopReason(run, compiled, 17));

        assertEquals("record_unavailable", thrown.code());
        verify(segmentService, never()).matchesEntity(
            7, 17, "person", null, 23);
    }

    private static WorkflowRun run(String recordType, int recordId) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setRecordType(recordType);
        run.setRecordId(recordId);
        return run;
    }
}
