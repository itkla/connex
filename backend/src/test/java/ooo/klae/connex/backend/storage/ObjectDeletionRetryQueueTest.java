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
    @Mock PlacementRegistry placementRegistry;
    @Mock TenantCatalogResolver tenantCatalogResolver;
    @Mock WorkspaceMapper workspaceMapper;

    private final TenantContext tenantContext = new TenantContext();
    private ObjectStorageProperties properties;
    private ObjectDeletionRetryQueue queue;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.setDeleteRetryBatchSize(2);
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        queue = new ObjectDeletionRetryQueue(
            objectStorage,
            properties,
            tenantQueueMapper,
            userQueueMapper,
            placementRegistry,
            tenantWorkScope,
            Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void clearContext() {
        tenantContext.clear();
    }

    @Test
    void persistsFailedTenantDeletionInRequestCatalog() {
        tenantContext.set(7, 3, 9, "owner", "tenant_catalog");
        AtomicReference<String> catalog = new AtomicReference<>();
        when(tenantQueueMapper.countPending(7)).thenReturn(1L);
        doAnswer(invocation -> {
            catalog.set(tenantContext.getCatalog());
            return 1;
        }).when(tenantQueueMapper).enqueue(anyInt(), any(), any());
        doThrow(new ObjectStorageException("unavailable"))
            .when(objectStorage).delete("workspaces/7/attachments/object.pdf");

        queue.enqueueAndProcessTenant(7, "workspaces/7/attachments/object.pdf");

        assertEquals("tenant_catalog", catalog.get());
        verify(tenantQueueMapper).rescheduleByKey(
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
            return 1;
        }).when(userQueueMapper).enqueue(any(), any());

        queue.enqueueAndProcessUser("users/9/profile-images/object.png");

        assertNull(catalog.get());
        verify(userQueueMapper).deleteByKey("users/9/profile-images/object.png");
    }

    @Test
    void retrySweepPinsTenantCatalogAndRemovesSuccessfulTask() {
        String key = "workspaces/7/attachments/object.pdf";
        AtomicReference<String> enumerationCatalog = new AtomicReference<>();
        AtomicReference<String> taskCatalog = new AtomicReference<>();
        when(placementRegistry.activeCatalogs()).thenReturn(List.of("tenant_catalog"));
        when(userQueueMapper.findDue(any(), anyInt())).thenReturn(List.of());
        when(tenantQueueMapper.workspaceIdsWithDueTasks(any(), anyInt())).thenAnswer(invocation -> {
            enumerationCatalog.set(tenantContext.getCatalog());
            return List.of(7);
        });
        when(tenantQueueMapper.findDue(org.mockito.ArgumentMatchers.eq(7), any(), anyInt()))
            .thenAnswer(invocation -> {
                taskCatalog.set(tenantContext.getCatalog());
                return List.of(new ObjectDeletionTask(11, 7, key, 2));
            });

        queue.retryPending();

        assertEquals("tenant_catalog", enumerationCatalog.get());
        assertEquals("tenant_catalog", taskCatalog.get());
        verify(objectStorage).delete(key);
        verify(tenantQueueMapper).deleteById(7, 11);
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
