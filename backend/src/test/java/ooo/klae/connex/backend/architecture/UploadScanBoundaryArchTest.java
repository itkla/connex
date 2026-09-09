package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.LegacyUploadMigrationTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import ooo.klae.connex.backend.storage.ScannedUpload;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;

/**
 * Pins the proof-carrying malware-scan boundary around normal attachment object writes.
 */
class UploadScanBoundaryArchTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path STORAGE_PACKAGE = SOURCE_ROOT.resolve(
            "ooo/klae/connex/backend/storage");

    @Test
    void scannedUploadConstructionStaysInsideTheStoragePackage() throws IOException {
        List<Path> violations;
        try (var sources = Files.walk(SOURCE_ROOT)) {
            violations = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getParent().equals(STORAGE_PACKAGE))
                    .filter(path -> read(path).contains("new ScannedUpload("))
                    .toList();
        }

        assertTrue(violations.isEmpty(),
                "Only the storage package may construct ScannedUpload: " + violations);
    }

    @Test
    void managedObjectServiceHasNoRawAttachmentStoreMethod() {
        List<String> violations = java.util.Arrays.stream(
                        ManagedObjectService.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("storeAttachment"))
                .toList();

        assertTrue(violations.isEmpty(),
                "ManagedObjectService must expose no storeAttachment methods: " + violations);
    }

    @Test
    void allAttachmentWritersRejectUnscannedInspectionProofs() throws NoSuchMethodException {
        List<Method> methods = java.util.Arrays.stream(
                        ManagedObjectService.class.getDeclaredMethods())
                .filter(method -> java.util.Arrays.asList(method.getParameterTypes())
                        .contains(InspectedUpload.class))
                .toList();
        assertTrue(methods.isEmpty(), "No attachment writer may accept inspection without scanning");
        Method migration = ManagedObjectService.class.getDeclaredMethod(
                "storeMigratedAttachment", int.class, int.class, String.class, ScannedUpload.class);
        assertFalse(Modifier.isPublic(migration.getModifiers()));
        assertFalse(Modifier.isProtected(migration.getModifiers()));
        assertFalse(Modifier.isPrivate(migration.getModifiers()));
    }

    @Test
    void inspectedAttachmentStoreRequiresScannedUpload() throws NoSuchMethodException {
        Method method = ManagedObjectService.class.getDeclaredMethod(
                "storeInspectedAttachment", int.class, ScannedUpload.class);

        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(ScannedUpload.class, method.getParameterTypes()[1]);
    }

    @Test
    void legacyScanEntrySuspendsMetadataTransactions() {
        Method migration = java.util.Arrays.stream(LegacyUploadMigrationTransaction.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("migrateAttachment")).findFirst().orElseThrow();
        Transactional transaction = migration.getAnnotation(Transactional.class);
        org.junit.jupiter.api.Assertions.assertNotNull(transaction);
        assertEquals(Propagation.NOT_SUPPORTED, transaction.propagation());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
