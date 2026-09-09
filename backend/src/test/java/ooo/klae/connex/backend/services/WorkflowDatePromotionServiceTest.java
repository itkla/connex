package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowDateEnrollment;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.mappers.WorkflowDateEnrollmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowDatePromotionServiceTest {

    @Mock private WorkflowDateEnrollmentMapper enrollmentMapper;
    @Mock private WorkflowTriggerOutboxMapper outboxMapper;
    @Mock private WorkflowVersionMapper versionMapper;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;

    private WorkflowDatePromotionService service;
    private WorkflowDateEnrollment enrollment;

    @BeforeEach
    void setUp() {
        service = new WorkflowDatePromotionService(
            enrollmentMapper, outboxMapper, versionMapper, canonicalizer);
        enrollment = new WorkflowDateEnrollment();
        enrollment.setId(41L);
        enrollment.setWorkspaceId(7);
        enrollment.setWorkflowId(11);
        enrollment.setWorkflowVersionId(29L);
        enrollment.setWorkflowRuntimeGeneration(5L);
        enrollment.setRecordType("deal");
        enrollment.setRecordId(19);
        enrollment.setDateField("expectedCloseDate");
        enrollment.setSourceDate(LocalDate.of(2027, 3, 31));
        enrollment.setScheduledLocalDate(LocalDate.of(2027, 3, 1));
        enrollment.setDueAt(LocalDateTime.of(2027, 3, 1, 19, 0));
        enrollment.setState("planned");
        when(enrollmentMapper.findDuePlannedIdForUpdate(7)).thenReturn(41L);
        when(enrollmentMapper.getByIdForUpdate(7, 41L)).thenReturn(enrollment);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(29L);
        version.setDefinitionJson("definition");
        when(versionMapper.getById(7, 11, 29L)).thenReturn(version);
        when(canonicalizer.parseDefinition("definition"))
            .thenReturn(dateDefinition("Pacific/Honolulu"));
    }

    @Test
    void promotesTheImmediatelyPreviousLocalDayWithEnrollmentDedupe() {
        when(enrollmentMapper.currentTimestamp(7, 11))
            .thenReturn(LocalDateTime.of(2027, 3, 3, 8, 0));
        when(enrollmentMapper.markQueued(7, 41L, LocalDateTime.of(2027, 3, 3, 8, 0)))
            .thenReturn(1);

        assertTrue(service.promoteOne(7));

        ArgumentCaptor<WorkflowTriggerOutbox> inserted =
            ArgumentCaptor.forClass(WorkflowTriggerOutbox.class);
        verify(outboxMapper).insert(inserted.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
            "date:41", inserted.getValue().getDedupeKey());
        org.junit.jupiter.api.Assertions.assertEquals(
            41L, inserted.getValue().getWorkflowDateEnrollmentId());
    }

    @Test
    void marksOlderLocalOccurrencesMissedWithoutCreatingOutboxWork() {
        when(enrollmentMapper.currentTimestamp(7, 11))
            .thenReturn(LocalDateTime.of(2027, 3, 4, 8, 0));

        assertTrue(service.promoteOne(7));

        verify(enrollmentMapper).markMissed(
            7, 41L, LocalDateTime.of(2027, 3, 4, 8, 0));
        verify(outboxMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    private static WorkflowDefinition dateDefinition(String timezone) {
        ooo.klae.connex.backend.dto.RuleTrigger trigger =
            new ooo.klae.connex.backend.dto.RuleTrigger();
        trigger.setType("date");
        trigger.setDateField("expectedCloseDate");
        trigger.setOffsetDays(-30);
        trigger.setLocalTime("09:00");
        trigger.setTimezone(timezone);
        trigger.setAllowManualRuns(false);
        return new WorkflowDefinition(
            2,
            "trigger",
            java.util.List.of(new ooo.klae.connex.backend.dto.WorkflowNode.Trigger(
                "trigger", trigger)),
            java.util.List.of(),
            java.util.List.of(),
            null,
            null);
    }
}
