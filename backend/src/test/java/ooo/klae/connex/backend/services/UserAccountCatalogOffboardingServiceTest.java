package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class UserAccountCatalogOffboardingServiceTest {
    @Mock private DepartureApprovalGuard guard;
    @Mock private PlacementRegistry placementRegistry;
    @Mock private UserOffboardingService offboarding;
    @Mock private ProviderCapturePurgeService capturePurge;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private PlatformTransactionManager transactionManager;
    @InjectMocks private UserAccountCatalogOffboardingService service;

    private final AtomicReference<String> catalog = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(placementRegistry.activeCatalogs()).thenReturn(List.of("catalog_a", "catalog_b"));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(tenantWorkScope.withCatalog(any(), any())).thenAnswer(invocation -> {
            String previous = catalog.getAndSet(invocation.getArgument(0));
            try {
                Supplier<?> work = invocation.getArgument(1);
                return work.get();
            } finally {
                catalog.set(previous);
            }
        });
    }

    @Test
    void preflightAndErasureVisitEveryCatalogWithTheGuardBeforeDestructiveWork() {
        AtomicInteger authoredChecks = new AtomicInteger();
        List<String> authoredCatalogs = List.of("catalog_a", "catalog_b", "catalog_a", "catalog_b");
        doAnswer(invocation -> {
            assertEquals(authoredCatalogs.get(authoredChecks.getAndIncrement()), catalog.get());
            return null;
        }).when(offboarding).assertNoAuthoredContent(9);
        doAnswer(invocation -> {
            assertEquals("catalog_a", catalog.get());
            return null;
        }).doAnswer(invocation -> {
            assertEquals("catalog_b", catalog.get());
            return null;
        }).when(offboarding).eraseOrgDataReferences(9);

        service.assertNoAuthoredContent(9);
        service.eraseReferences(9);

        InOrder order = inOrder(tenantWorkScope, guard, offboarding, capturePurge);
        for (String handle : List.of("catalog_a", "catalog_b")) {
            order.verify(tenantWorkScope).withCatalog(eq(handle), any());
            order.verify(offboarding).assertNoAuthoredContent(9);
            order.verify(guard).assertCanErase(9);
        }
        for (String handle : List.of("catalog_a", "catalog_b")) {
            order.verify(tenantWorkScope).withCatalog(eq(handle), any());
            order.verify(guard).assertCanErase(9);
            order.verify(capturePurge).purgeAccountCatalog(9, "google");
            order.verify(capturePurge).purgeAccountCatalog(9, "microsoft");
            order.verify(capturePurge).clearAccountReferencesInCatalog(9);
            order.verify(offboarding).assertNoAuthoredContent(9);
            order.verify(offboarding).eraseOrgDataReferences(9);
        }
    }

    @Test
    void aCatalogGuardRefusalPrecedesAllCaptureAndReferenceErasureInThatCatalog() {
        doAnswer(invocation -> {
            if ("catalog_b".equals(catalog.get())) {
                throw new ConflictException("Reassign first");
            }
            return null;
        }).when(guard).assertCanErase(9);

        assertThrows(ConflictException.class, () -> service.eraseReferences(9));

        InOrder order = inOrder(tenantWorkScope, guard, offboarding, capturePurge);
        order.verify(tenantWorkScope).withCatalog(eq("catalog_a"), any());
        order.verify(guard).assertCanErase(9);
        order.verify(capturePurge).purgeAccountCatalog(9, "google");
        order.verify(capturePurge).purgeAccountCatalog(9, "microsoft");
        order.verify(capturePurge).clearAccountReferencesInCatalog(9);
        order.verify(offboarding).assertNoAuthoredContent(9);
        order.verify(offboarding).eraseOrgDataReferences(9);
        order.verify(tenantWorkScope).withCatalog(eq("catalog_b"), any());
        order.verify(guard).assertCanErase(9);
        order.verifyNoMoreInteractions();
    }
}
