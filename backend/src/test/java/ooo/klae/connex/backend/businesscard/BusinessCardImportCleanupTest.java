package ooo.klae.connex.backend.businesscard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.BusinessCardImportRequestMapper;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class BusinessCardImportCleanupTest {
    @Mock BusinessCardImportRequestMapper mapper;
    @Mock PlacementRegistry placementRegistry;
    @Mock TenantWorkScope tenantWorkScope;

    private BusinessCardProperties properties;
    private BusinessCardImportCleanup cleanup;

    @BeforeEach
    void setUp() {
        properties = new BusinessCardProperties();
        properties.setIdempotencyCleanupBatchSize(3);
        cleanup = new BusinessCardImportCleanup(
            properties,
            mapper,
            placementRegistry,
            tenantWorkScope,
            Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));
        when(tenantWorkScope.withCatalog(any(), any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(1);
            return work.get();
        });
    }

    @Test
    void sweepsCompletedClaimsAcrossEveryActiveCatalogWithinTheBatchBound() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 15, 12, 0);
        when(placementRegistry.activeCatalogs()).thenReturn(Arrays.asList(null, "dedicated_org"));
        when(mapper.workspaceIdsWithExpired(cutoff, 3)).thenReturn(List.of(7, 8));
        when(mapper.deleteExpired(7, cutoff, 3)).thenReturn(2);
        when(mapper.deleteExpired(8, cutoff, 1)).thenReturn(1);

        cleanup.deleteExpired();

        verify(tenantWorkScope).withCatalog(eq(null), any());
        verify(tenantWorkScope).withCatalog(eq("dedicated_org"), any());
        verify(mapper, org.mockito.Mockito.times(2)).deleteExpired(7, cutoff, 3);
        verify(mapper, org.mockito.Mockito.times(2)).deleteExpired(8, cutoff, 1);
    }
}
