package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeRetentionServiceTest {

    @Mock private WorkflowTriggerOutboxMapper outboxMapper;
    @Mock private WorkflowRuntimeProperties properties;

    @InjectMocks private WorkflowRuntimeRetentionService service;

    @Test
    void retentionPurgesResolvedOutboxesWithoutDeletingUnresolvedDeadDiagnostics() {
        when(properties.maxRetentionDeletesPerWorkspace()).thenReturn(100);
        when(properties.completedOutboxRetention()).thenReturn(Duration.ofDays(7));
        when(outboxMapper.purgeCompletedBefore(
            eq(7), any(LocalDateTime.class), eq(100))).thenReturn(4);
        LocalDateTime earliestCutoff = LocalDateTime.now().minusDays(7);

        assertEquals(4, service.purge(7));

        LocalDateTime latestCutoff = LocalDateTime.now().minusDays(7);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxMapper).purgeCompletedBefore(eq(7), cutoff.capture(), eq(100));
        assertFalse(cutoff.getValue().isBefore(earliestCutoff));
        assertFalse(cutoff.getValue().isAfter(latestCutoff));
        verifyNoMoreInteractions(outboxMapper);
    }
}
