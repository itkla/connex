package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.WorkflowDateEnrollment;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowDateEnrollmentMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowDateReconciliationServiceTest {

    @Mock private WorkflowDateEnrollmentMapper enrollmentMapper;
    @Mock private DealMapper dealMapper;
    @Mock private SegmentMapper segmentMapper;
    @Mock private WorkflowDateScheduleService scheduleService;
    @Mock private WorkflowRuntimeProperties properties;

    private WorkflowDateReconciliationService service;
    private WorkflowTriggerOutbox outbox;
    private WorkflowVersion version;
    private RuleTrigger trigger;

    @BeforeEach
    void setUp() {
        service = new WorkflowDateReconciliationService(
            enrollmentMapper, dealMapper, segmentMapper, scheduleService, properties);
        outbox = new WorkflowTriggerOutbox();
        outbox.setWorkspaceId(7);
        outbox.setWorkflowId(11);
        outbox.setWorkflowVersionId(29L);
        outbox.setWorkflowRuntimeGeneration(5L);
        outbox.setRecordId(19);
        version = new WorkflowVersion();
        version.setId(29L);
        trigger = new RuleTrigger();
        trigger.setType("date");
        trigger.setDateField("expectedCloseDate");
    }

    @Test
    void sourceDateChangeSupersedesTheOldPeriodAndPlansTheNewPeriod() {
        LocalDateTime now = LocalDateTime.of(2027, 2, 1, 12, 0);
        Deal deal = new Deal();
        deal.setExpectedCloseDate("2027-03-31");
        WorkflowDateEnrollment old = enrollment(41L, LocalDate.of(2027, 2, 28), "planned");
        when(dealMapper.getDealByIdForUpdate(7, 19)).thenReturn(deal);
        when(enrollmentMapper.getPendingByRecordForUpdate(7, 11, 19))
            .thenReturn(List.of(old));
        when(enrollmentMapper.currentTimestamp(7, 11)).thenReturn(now);
        when(scheduleService.resolve(LocalDate.of(2027, 3, 31), trigger)).thenReturn(
            new WorkflowDateScheduleService.Schedule(
                LocalDate.of(2027, 3, 31),
                LocalDate.of(2027, 3, 1),
                LocalDateTime.of(2027, 3, 1, 19, 0),
                ZoneId.of("Pacific/Honolulu")));
        when(enrollmentMapper.getByPeriodForUpdate(
            7, 11, 19, "expectedCloseDate", LocalDate.of(2027, 3, 31)))
            .thenReturn(null);

        service.reconcile(outbox, version, trigger);

        verify(enrollmentMapper).supersede(7, 41L, now);
        ArgumentCaptor<WorkflowDateEnrollment> inserted =
            ArgumentCaptor.forClass(WorkflowDateEnrollment.class);
        verify(enrollmentMapper).insert(inserted.capture());
        assertEquals("planned", inserted.getValue().getState());
        assertEquals(LocalDate.of(2027, 3, 31), inserted.getValue().getSourceDate());
        assertEquals(5L, inserted.getValue().getWorkflowRuntimeGeneration());
    }

    @Test
    void deletedRecordTerminalizesBothPlannedAndQueuedPeriods() {
        LocalDateTime now = LocalDateTime.of(2027, 2, 1, 12, 0);
        WorkflowDateEnrollment planned = enrollment(41L, LocalDate.of(2027, 3, 1), "planned");
        WorkflowDateEnrollment queued = enrollment(42L, LocalDate.of(2027, 4, 1), "queued");
        when(dealMapper.getDealByIdForUpdate(7, 19)).thenReturn(null);
        when(enrollmentMapper.getPendingByRecordForUpdate(7, 11, 19))
            .thenReturn(List.of(planned, queued));
        when(enrollmentMapper.currentTimestamp(7, 11)).thenReturn(now);

        service.reconcile(outbox, version, trigger);

        verify(enrollmentMapper).supersede(7, 41L, now);
        verify(enrollmentMapper).markQueuedMissed(7, 42L, now);
        verify(scheduleService, never()).resolve(any(), any());
    }

    private static WorkflowDateEnrollment enrollment(
            long id, LocalDate sourceDate, String state) {
        WorkflowDateEnrollment enrollment = new WorkflowDateEnrollment();
        enrollment.setId(id);
        enrollment.setSourceDate(sourceDate);
        enrollment.setState(state);
        return enrollment;
    }
}
