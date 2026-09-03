package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.SequenceStep;
import ooo.klae.connex.backend.beans.SequenceStepContent;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.sequence.SequenceDto;
import ooo.klae.connex.backend.dto.sequence.SequenceRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepRequest;
import ooo.klae.connex.backend.dto.sequence.SequenceStepType;
import ooo.klae.connex.backend.exceptions.SequenceException;
import ooo.klae.connex.backend.mappers.SequenceMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class SequenceServiceTest {
    @Mock private SequenceMapper sequenceMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private SequenceMergeFieldResolver mergeFieldResolver;

    private SequenceService service;
    private User actor;

    @BeforeEach
    void setUp() {
        service = new SequenceService(
            sequenceMapper, workspaceService, authService, auditService, mergeFieldResolver);
        actor = new User();
        actor.setId(9);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(authService.getCurrentUser()).thenReturn(actor);
    }

    @Test
    void createStoresCanonicalOrderedDraftAndRechecksManagePermission() {
        AtomicReference<Sequence> storedSequence = new AtomicReference<>();
        List<SequenceStep> storedSteps = new ArrayList<>();
        List<SequenceStepContent> storedContents = new ArrayList<>();
        when(sequenceMapper.insertSequence(any())).thenAnswer(invocation -> {
            Sequence sequence = invocation.getArgument(0);
            sequence.setId(41);
            storedSequence.set(sequence);
            return 1;
        });
        when(sequenceMapper.insertStep(any())).thenAnswer(invocation -> {
            SequenceStep step = invocation.getArgument(0);
            step.setId(storedSteps.size() + 101L);
            storedSteps.add(step);
            return 1;
        });
        when(sequenceMapper.insertStepContent(any())).thenAnswer(invocation -> {
            storedContents.add(invocation.getArgument(0));
            return 1;
        });
        when(sequenceMapper.getVisibleSequence(7, 41, 9))
            .thenAnswer(invocation -> storedSequence.get());
        when(sequenceMapper.getSteps(7, 41)).thenAnswer(invocation -> List.copyOf(storedSteps));
        when(sequenceMapper.getStepContents(7, List.of(101L, 102L)))
            .thenAnswer(invocation -> List.copyOf(storedContents));

        SequenceDto created = service.create(request("UTC", LocalTime.of(9, 0), LocalTime.of(17, 0)));

        assertEquals(41, created.id());
        assertEquals(9, created.ownerId());
        assertEquals(List.of("send_email", "call_task"), storedSteps.stream()
            .map(SequenceStep::getStepType).toList());
        assertEquals(List.of(2, 1), storedSteps.stream()
            .map(SequenceStep::getDelayValue).toList());
        assertEquals(List.of("hours", "business_days"), storedSteps.stream()
            .map(SequenceStep::getDelayUnit).toList());
        assertEquals(List.of("automatic", "manual_completion"), storedSteps.stream()
            .map(SequenceStep::getAdvancePolicy).toList());
        assertEquals(List.of("en", "ja", "en"), storedContents.stream()
            .map(SequenceStepContent::getLocale).toList());
        assertEquals(List.of(2, 1), created.steps().stream()
            .map(step -> step.delayValue()).toList());
        assertEquals(List.of("hours", "business_days"), created.steps().stream()
            .map(step -> step.delayUnit()).toList());
        verify(workspaceService).lockAndRequirePermissions(
            7, java.util.Map.of(9, Set.of(Permission.SEQUENCE_MANAGE)));
    }

    @Test
    void getRechecksViewPermissionBeforeLoadingVisiblePayload() {
        Sequence sequence = new Sequence();
        sequence.setId(41);
        sequence.setWorkspaceId(7);
        sequence.setName("Visible");
        when(sequenceMapper.getVisibleSequence(7, 41, 9)).thenReturn(sequence);

        SequenceDto result = service.get(41);

        assertEquals(41, result.id());
        verify(workspaceService).requirePermission(7, 9, Permission.SEQUENCE_VIEW);
    }

    @Test
    void invalidIanaTimezoneFailsBeforePersistence() {
        assertThrows(SequenceException.class,
            () -> service.create(request("GMT+2", LocalTime.of(9, 0), LocalTime.of(17, 0))));
        verifyNoInteractions(sequenceMapper);
    }

    @Test
    void invalidSendWindowFailsBeforePersistence() {
        assertThrows(SequenceException.class,
            () -> service.create(request("UTC", LocalTime.NOON, LocalTime.NOON)));
        verifyNoInteractions(sequenceMapper);
    }

    @Test
    void manualStepRejectsAutomaticAdvancement() {
        SequenceStepRequest step = new SequenceStepRequest(
            SequenceStepType.CALL_TASK,
            1,
            "business_days",
            "automatic",
            List.of(new SequenceStepRequest.Content("en", null, "Call {{person.name}}", null)));
        SequenceRequest request = new SequenceRequest(
            "Follow up", null, "personal", "UTC", 31,
            LocalTime.of(9, 0), LocalTime.of(17, 0), List.of(step));

        assertThrows(SequenceException.class, () -> service.create(request));
        verifyNoInteractions(sequenceMapper);
    }

    private static SequenceRequest request(
            String timezone,
            LocalTime start,
            LocalTime end) {
        SequenceStepRequest step = new SequenceStepRequest(
            SequenceStepType.SEND_EMAIL,
            2,
            "hours",
            "automatic",
            List.of(
                new SequenceStepRequest.Content(
                    "en", "Hello {{person.name}}", "Body", "<p>Body</p>"),
                new SequenceStepRequest.Content(
                    "ja", "こんにちは {{person.name}}", "本文", "<p>本文</p>")));
        SequenceStepRequest followUp = new SequenceStepRequest(
            SequenceStepType.CALL_TASK,
            1,
            "business_days",
            "manual_completion",
            List.of(new SequenceStepRequest.Content(
                "en", null, "Call {{person.name}}", null)));
        return new SequenceRequest(
            "Outbound", "Purpose", "personal", timezone, 31, start, end,
            List.of(step, followUp));
    }
}
