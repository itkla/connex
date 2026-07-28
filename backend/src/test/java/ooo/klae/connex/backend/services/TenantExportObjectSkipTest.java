package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedTenantObject;
import ooo.klae.connex.backend.storage.StoredObject;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the export bundle's object-consistency policy: bytes that vanish are
 * tolerated only when the metadata row is provably gone too, every skip is
 * audited with the exact object key and counted separately from what the ZIP
 * actually contains, the manifest carries the enumerated total both counts must
 * reconcile against, the skip ceiling aborts a whole-bucket outage, and a
 * per-object admission refusal is retried instead of truncating a committed
 * response.
 */
@ExtendWith(MockitoExtension.class)
class TenantExportObjectSkipTest {
    private static final int ORG_ID = 4;
    private static final int WORKSPACE_ID = 6;
    private static final int ACTOR_ID = 2;
    private static final String OBJECT_KEY = "workspaces/6/attachments/token";
    private static final byte[] BINARY = "export-bytes".getBytes(StandardCharsets.UTF_8);
    private static final ActiveObjectReference REFERENCE = new ActiveObjectReference(
        OBJECT_KEY,
        "attachment",
        0,
        "/api/attachments/content/token",
        (long) BINARY.length);

    @Mock private OrgMemberService orgMemberService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private TenantLifecycleControlOperations controlOperations;
    @Mock private TenantExportTableReadTransaction readTransaction;
    @Mock private ManagedObjectService managedObjectService;
    @Mock private AuditService auditService;
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;

    private TenantLifecycleProperties properties;
    private TenantExportService exportService;

    @BeforeEach
    void setUp() {
        properties = new TenantLifecycleProperties();
        properties.setExportObjectReadTimeout(Duration.ofSeconds(2));
        TenantContext tenantContext = new TenantContext();
        TenantWorkScope tenantWorkScope =
            new TenantWorkScope(tenantContext, tenantCatalogResolver, workspaceMapper);
        TenantLifecycleAccess lifecycleAccess =
            new TenantLifecycleAccess(tenantWorkScope, tenantCatalogResolver, tenantContext);
        exportService = new TenantExportService(
            orgMemberService,
            sessionSecurityService,
            tenantWorkScope,
            controlOperations,
            lifecycleAccess,
            readTransaction,
            managedObjectService,
            auditService,
            properties,
            JsonMapper.builder().build(),
            Clock.system(ZoneOffset.UTC));
        when(controlOperations.acquireExport(ORG_ID, WORKSPACE_ID, ACTOR_ID)).thenReturn(
            new AcquiredWorkspace(
                new WorkspaceLifecycleRef(WORKSPACE_ID, ORG_ID, "Export", "export", "active"),
                new OperationLease(ORG_ID, WORKSPACE_ID, "export", "token")));
        when(readTransaction.count(anyInt(), any())).thenReturn(0L);
        when(readTransaction.activeObjects(eq(WORKSPACE_ID), eq(""), anyInt()))
            .thenReturn(List.of(REFERENCE));
        when(readTransaction.activeObjects(eq(WORKSPACE_ID), eq(OBJECT_KEY), anyInt()))
            .thenReturn(List.of());
    }

    @Test
    void anObjectWhoseMetadataRowIsProvablyGoneIsSkippedAuditedAndCountedSeparately()
            throws Exception {
        stubOpenFailure(new ResourceNotFoundException("Stored file was not found"));
        when(readTransaction.activeObject(WORKSPACE_ID, OBJECT_KEY)).thenReturn(null);

        String manifest = exportManifest();

        assertEquals(1, countManifestValue(manifest, "enumeratedObjectCount"));
        assertEquals(0, countManifestValue(manifest, "objectCount"));
        assertEquals(1, countManifestValue(manifest, "skippedObjectCount"));
        verify(auditService).recordStrictIndependentScoped(
            eq("org.workspace.export.object_skipped"),
            anyString(),
            eq(WORKSPACE_ID),
            eq(null),
            eq(ORG_ID),
            anyString(),
            anyString(),
            eq(Map.of(
                "objectKey", OBJECT_KEY,
                "objectKind", "attachment",
                "objectOwnerId", 0,
                "skippedObjectCount", 1L)));
    }

    @Test
    void missingBytesWithASurvivingMetadataRowStillFailHard() throws Exception {
        stubOpenFailure(new ResourceNotFoundException("Stored file was not found"));
        when(readTransaction.activeObject(WORKSPACE_ID, OBJECT_KEY)).thenReturn(REFERENCE);

        assertThrows(ResourceNotFoundException.class, this::exportManifest);

        verify(auditService, never()).recordStrictIndependentScoped(
            eq("org.workspace.export.object_skipped"),
            anyString(),
            anyInt(),
            any(),
            anyInt(),
            anyString(),
            anyString(),
            any());
    }

    @Test
    void exceedingTheSkipCeilingAbortsTheBundle() throws Exception {
        properties.setMaxSkippedExportObjects(0);
        stubOpenFailure(new ResourceNotFoundException("Stored file was not found"));
        when(readTransaction.activeObject(WORKSPACE_ID, OBJECT_KEY)).thenReturn(null);

        assertThrows(IllegalStateException.class, this::exportManifest);
    }

    @Test
    void aTransientAdmissionRefusalIsRetriedInsteadOfTruncatingTheBundle() throws Exception {
        when(managedObjectService.openTenantExportObject(
                eq(WORKSPACE_ID),
                eq(ACTOR_ID),
                eq(REFERENCE),
                any()))
            .thenThrow(new TooManyRequestsException("busy"))
            .thenReturn(managedObject());

        Map<String, byte[]> entries = exportEntries();

        assertArrayEquals(BINARY, entries.get("objects/" + OBJECT_KEY));
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertEquals(1, countManifestValue(manifest, "enumeratedObjectCount"));
        assertEquals(1, countManifestValue(manifest, "objectCount"));
        assertEquals(0, countManifestValue(manifest, "skippedObjectCount"));
    }

    private void stubOpenFailure(RuntimeException failure) {
        when(managedObjectService.openTenantExportObject(
                eq(WORKSPACE_ID),
                eq(ACTOR_ID),
                eq(REFERENCE),
                any()))
            .thenThrow(failure);
    }

    private static ManagedTenantObject managedObject() {
        return new ManagedTenantObject(
            OBJECT_KEY,
            new StoredObject(new ByteArrayInputStream(BINARY), BINARY.length),
            BINARY.length);
    }

    private String exportManifest() throws Exception {
        return new String(exportEntries().get("manifest.json"), StandardCharsets.UTF_8);
    }

    private Map<String, byte[]> exportEntries() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exportService.prepare(ORG_ID, WORKSPACE_ID, ACTOR_ID).writeTo(output);
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static long countManifestValue(String manifest, String field) {
        String token = "\"" + field + "\":";
        int start = manifest.indexOf(token);
        assertTrue(start >= 0, field + " is missing from " + manifest);
        int cursor = start + token.length();
        int end = cursor;
        while (end < manifest.length() && Character.isDigit(manifest.charAt(end))) {
            end++;
        }
        return Long.parseLong(manifest.substring(cursor, end));
    }
}
