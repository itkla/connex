package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ManagedObjectRollbackIntegrationTest {
    private static final Path OBJECT_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"), "connex-test-object-storage")
        .toAbsolutePath()
        .normalize();

    @Autowired private ManagedObjectService managedObjectService;
    @Autowired private ObjectStorage objectStorage;
    @Autowired private ObjectDeletionQueueMapper deletionQueueMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.object-storage.filesystem-root", OBJECT_ROOT::toString);
        registry.add("connex.object-storage.ambiguous-write-cleanup-delay-ms", () -> 300_000);
    }

    @Test
    void rollbackPreservesThePrecommittedCleanupTombstone() {
        Workspace workspace = workspaceMapper.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Managed object rollback workspace");
            workspace.setSlug("default");
            workspaceMapper.insert(workspace);
        }
        assertTrue(workspace.getId() > 0);
        int workspaceId = workspace.getId();
        AtomicReference<String> key = new AtomicReference<>();

        try {
            assertThrows(IntentionalRollback.class, () -> tenantWorkScope.inWorkspace(
                workspaceId,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    ManagedObjectService.StoredBinary stored = managedObjectService.storeAttachment(
                        workspaceId,
                        "rollback.pdf",
                        "application/pdf",
                        "%PDF-1.4\nrollback".getBytes(StandardCharsets.UTF_8));
                    String token = stored.url().substring(stored.url().lastIndexOf('/') + 1);
                    key.set("workspaces/" + workspaceId + "/attachments/" + token);
                    throw new IntentionalRollback();
                })));

            tenantWorkScope.inWorkspace(workspaceId, () -> {
                ObjectDeletionTask task = deletionQueueMapper.findDue(
                        workspaceId, LocalDateTime.of(2100, 1, 1, 0, 0), 100)
                    .stream()
                    .filter(candidate -> candidate.objectKey().equals(key.get()))
                    .findFirst()
                    .orElseThrow();
                assertEquals(2, task.deletePassesRemaining());
                assertTrue(Files.isRegularFile(OBJECT_ROOT.resolve(key.get() + ".object")));
            });
        } finally {
            if (key.get() != null) {
                tenantWorkScope.inWorkspace(workspaceId, () -> {
                    objectStorage.delete(key.get());
                    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        deletionQueueMapper.deleteByKey(workspaceId, key.get()));
                });
            }
        }
    }

    private static final class IntentionalRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
