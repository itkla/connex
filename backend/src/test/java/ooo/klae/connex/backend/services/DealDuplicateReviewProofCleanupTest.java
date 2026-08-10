package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import ooo.klae.connex.backend.mappers.DealDuplicateReviewProofMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class DealDuplicateReviewProofCleanupTest {
    @Mock private DealDuplicateReviewProofMapper mapper;
    @Mock private PlacementRegistry placementRegistry;
    @Mock private TenantWorkScope tenantWorkScope;

    private DealDuplicateReviewProofCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new DealDuplicateReviewProofCleanup(
            mapper, placementRegistry, tenantWorkScope);
        org.mockito.Mockito.lenient()
            .when(tenantWorkScope.withCatalog(any(), any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(1);
            return work.get();
        });
    }

    @Test
    void givesEachActiveCatalogAWorkspaceBoundedCleanupPass() {
        when(placementRegistry.activeCatalogs()).thenReturn(Arrays.asList(null, "cnx_a"));
        when(mapper.workspaceIdsWithExpired(100)).thenReturn(List.of(7, 8));

        cleanup.deleteExpired();

        verify(tenantWorkScope).withCatalog(eq(null), any());
        verify(tenantWorkScope).withCatalog(eq("cnx_a"), any());
        verify(mapper, org.mockito.Mockito.times(2)).deleteExpired(7, 100);
        verify(mapper, org.mockito.Mockito.times(2)).deleteExpired(8, 100);
    }

    @Test
    void scheduleDefaultsToOneMinute() throws Exception {
        Scheduled scheduled = DealDuplicateReviewProofCleanup.class
            .getMethod("deleteExpired")
            .getAnnotation(Scheduled.class);

        assertEquals(
            "${connex.duplicate-preflight.review-proof-cleanup-delay:PT1M}",
            scheduled.fixedDelayString());
    }
}
