package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class ObjectDeletionRetryQueueTest {
    @Mock ObjectStorage objectStorage;
    @Mock ObjectDeletionQueueMapper tenantQueueMapper;
    @Mock UserObjectDeletionQueueMapper userQueueMapper;
    @Mock ObjectDeletionTransactionExecutor transactionExecutor;
    @Mock PlacementRegistry placementRegistry;
    @Mock TenantCatalogResolver tenantCatalogResolver;
    @Mock WorkspaceMapper workspaceMapper;

    private final TenantContext tenantContext = new TenantContext();
    private ObjectStorageProperties properties;
    private ObjectDeletionRetryQueue queue;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.setDeleteRetryBatchSize(3);
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        queue = new ObjectDeletionRetryQueue(
            objectStorage,
            properties,
            tenantQueueMapper,
            userQueueMapper,
            transactionExecutor,
            placementRegistry,
            tenantWorkScope,
            Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void clearContext() {
        tenantContext.clear();
    }

    @Test
    void persistsTenantDeletionInAnIndependentTransactionInTheRequestCatalog() {
        tenantContext.set(7, 3, 9, "owner", "tenant_catalog");
        AtomicReference<String> catalog = new AtomicReference<>();
        doAnswer(invocation -> {
            catalog.set(tenantContext.getCatalog());
            return null;
        }).when(transactionExecutor).enqueueTenant(anyInt(), any(), any());

        queue.enqueueAndProcessTenant(7, "workspaces/7/attachments/object.pdf");

        assertEquals("tenant_catalog", catalog.get());
        verify(transactionExecutor).processTenant(
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.eq("workspaces/7/attachments/object.pdf"),
            any());
    }

    @Test
    void userQueueWritesStayOnControlCatalog() {
        tenantContext.set(7, 3, 9, "owner", "tenant_catalog");
        AtomicReference<String> catalog = new AtomicReference<>("unset");
        doAnswer(invocation -> {
            catalog.set(tenantContext.getCatalog());
            return null;
        }).when(transactionExecutor).enqueueUser(any(), any());

        queue.enqueueAndProcessUser("users/9/profile-images/object.png");

        assertNull(catalog.get());
        verify(transactionExecutor).processUser(
            org.mockito.ArgumentMatchers.eq("users/9/profile-images/object.png"), any());
    }

    @Test
    void retrySweepPinsTenantCatalogAndDelegatesEachTask() {
        String key = "workspaces/7/attachments/object.pdf";
        ObjectDeletionTask task = new ObjectDeletionTask(11, 7, key, 2);
        AtomicReference<String> enumerationCatalog = new AtomicReference<>();
        AtomicReference<String> taskCatalog = new AtomicReference<>();
        when(placementRegistry.activeCatalogs()).thenReturn(List.of("tenant_catalog"));
        when(userQueueMapper.findDue(any(), anyInt())).thenReturn(List.of());
        when(tenantQueueMapper.workspaceIdsWithDueTasks(any(), anyInt())).thenAnswer(invocation -> {
            enumerationCatalog.set(tenantContext.getCatalog());
            return List.of(7);
        });
        when(tenantQueueMapper.findDue(org.mockito.ArgumentMatchers.eq(7), any(), anyInt()))
            .thenReturn(List.of(task));
        doAnswer(invocation -> {
            taskCatalog.set(tenantContext.getCatalog());
            return null;
        }).when(transactionExecutor).retryTenant(org.mockito.ArgumentMatchers.eq(task), any());

        queue.retryPending();

        assertEquals("tenant_catalog", enumerationCatalog.get());
        assertEquals("tenant_catalog", taskCatalog.get());
    }

    @Test
    void oneFailedRetryDoesNotAbortLaterTasks() {
        ObjectDeletionTask first = new ObjectDeletionTask(
            11, 7, "workspaces/7/attachments/first.pdf", 2);
        ObjectDeletionTask second = new ObjectDeletionTask(
            12, 7, "workspaces/7/attachments/second.pdf", 2);
        when(placementRegistry.activeCatalogs()).thenReturn(Collections.singletonList(null));
        when(userQueueMapper.findDue(any(), anyInt())).thenReturn(List.of());
        when(tenantQueueMapper.workspaceIdsWithDueTasks(any(), anyInt())).thenReturn(List.of(7));
        when(tenantQueueMapper.findDue(org.mockito.ArgumentMatchers.eq(7), any(), anyInt()))
            .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("database unavailable"))
            .when(transactionExecutor).retryTenant(org.mockito.ArgumentMatchers.eq(first), any());

        queue.retryPending();

        verify(transactionExecutor).retryTenant(org.mockito.ArgumentMatchers.eq(second), any());
    }

    @Test
    void directTenantFallbackReleasesQuotaAfterPhysicalDeletion() {
        tenantContext.set(7, 3, 9, "owner", "tenant_catalog");
        String key = "workspaces/7/attachments/object.pdf";
        doThrow(new IllegalStateException("database unavailable"))
            .when(transactionExecutor).enqueueTenant(7, key,
                java.time.LocalDateTime.of(2026, 7, 14, 12, 0));

        queue.enqueueAndProcessTenant(7, key);

        verify(objectStorage).delete(key);
        verify(transactionExecutor).releaseTenantQuota(7, key);
    }

    @Test
    void retrySweepAlwaysVisitsDefaultCatalog() {
        when(placementRegistry.activeCatalogs()).thenReturn(Collections.singletonList(null));
        when(userQueueMapper.findDue(any(), anyInt())).thenReturn(List.of());
        when(tenantQueueMapper.workspaceIdsWithDueTasks(any(), anyInt())).thenReturn(List.of());

        queue.retryPending();

        verify(tenantQueueMapper).workspaceIdsWithDueTasks(any(), anyInt());
    }
}
