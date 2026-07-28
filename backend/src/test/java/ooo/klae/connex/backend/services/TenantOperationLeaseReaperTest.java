package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Pins the scheduled sweep that keeps a crashed export from wedging the export
 * budget: it runs on the control catalog and never lets a failed sweep escape
 * into the scheduler.
 */
@ExtendWith(MockitoExtension.class)
class TenantOperationLeaseReaperTest {

    @Mock private TenantLifecycleControlOperations controlOperations;
    @Mock private TenantWorkScope tenantWorkScope;

    private TenantOperationLeaseReaper reaper;

    @BeforeEach
    void setUp() {
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        reaper = new TenantOperationLeaseReaper(controlOperations, tenantWorkScope);
    }

    @Test
    void theSweepRunsUnroutedOnTheControlCatalog() {
        when(controlOperations.reapStaleExportLeases()).thenReturn(2);

        reaper.reapStaleExportLeases();

        verify(tenantWorkScope).unrouted(any());
        verify(controlOperations).reapStaleExportLeases();
    }

    @Test
    void aFailedSweepNeverEscapesIntoTheScheduler() {
        when(controlOperations.reapStaleExportLeases())
            .thenThrow(new IllegalStateException("lease sweep failed"));

        assertDoesNotThrow(() -> reaper.reapStaleExportLeases());
    }
}
