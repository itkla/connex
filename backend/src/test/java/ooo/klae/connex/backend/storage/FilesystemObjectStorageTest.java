package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemObjectStorageTest {
    @TempDir Path temporaryDirectory;

    private Path root;
    private FilesystemObjectStorage storage;

    @BeforeEach
    void setUp() {
        root = temporaryDirectory.resolve("private-objects");
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setFilesystemRoot(root.toString());
        storage = new FilesystemObjectStorage(properties);
    }

    @Test
    void roundTripsAndDeletesPrivateObject() throws Exception {
        byte[] bytes = "durable private content".getBytes(StandardCharsets.UTF_8);
        String key = "workspaces/17/attachments/example.pdf";

        storage.put(key, source(bytes), "application/pdf", sha256(bytes));

        try (StoredObject object = storage.get(key); InputStream input = object.inputStream()) {
            assertEquals(bytes.length, object.contentLength());
            assertArrayEquals(bytes, input.readAllBytes());
        }
        assertTrue(storage.isReady());
        storage.delete(key);
        storage.delete(key);
        assertThrows(ObjectStorageNotFoundException.class, () -> storage.get(key));
    }

    @Test
    void appliesOwnerOnlyPermissionsWherePosixIsAvailable() throws Exception {
        byte[] bytes = { 1, 2, 3 };
        String key = "workspaces/4/attachments/private.bin";
        storage.put(key, source(bytes), "application/octet-stream", sha256(bytes));

        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(root));
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(root.resolve(key + ".object")));
        }
    }

    @Test
    void atomicallyReplacesAReplaySafeMigrationTarget() throws Exception {
        String key = "workspaces/17/attachments/replayed.pdf";
        byte[] original = {1, 2, 3};
        byte[] replacement = {4, 5, 6, 7};

        storage.put(key, source(original), "application/pdf", sha256(original));
        storage.put(key, source(replacement), "application/pdf", sha256(replacement));

        try (StoredObject object = storage.get(key); InputStream input = object.inputStream()) {
            assertArrayEquals(replacement, input.readAllBytes());
        }
    }

    @Test
    void rejectsTraversalAndChecksumMismatchWithoutPublishingObject() throws Exception {
        byte[] bytes = { 1, 2, 3 };
        assertThrows(
            ObjectStorageException.class,
            () -> storage.put(
                "workspaces/4/../../outside",
                source(bytes),
                "application/octet-stream",
                sha256(bytes)));

        String key = "workspaces/4/attachments/mismatch.bin";
        assertThrows(
            ObjectStorageException.class,
            () -> storage.put(
                key,
                source(bytes),
                "application/octet-stream",
                new byte[32]));
        assertFalse(Files.exists(root.resolve(key + ".object")));
    }

    @Test
    void rejectsSymbolicLinksInsideStorageRoot() throws Exception {
        Files.createDirectories(root);
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(root.resolve("workspaces"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }
        byte[] bytes = { 1, 2, 3 };

        assertThrows(
            ObjectStorageException.class,
            () -> storage.put(
                "workspaces/4/attachments/private.bin",
                source(bytes),
                "application/octet-stream",
                sha256(bytes)));
        assertFalse(Files.exists(outside.resolve("4/attachments/private.bin.object")));
    }

    @Test
    void neverReadsOrDeletesThroughFinalPathSymbolicLink() throws Exception {
        String key = "workspaces/4/attachments/private.bin";
        Path target = root.resolve(key + ".object");
        Files.createDirectories(target.getParent());
        Path outside = temporaryDirectory.resolve("outside.bin");
        Files.write(outside, new byte[] { 7, 8, 9 });
        try {
            Files.createSymbolicLink(target, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThrows(ObjectStorageNotFoundException.class, () -> storage.get(key));
        assertThrows(ObjectStorageException.class, () -> storage.delete(key));
        assertArrayEquals(new byte[] { 7, 8, 9 }, Files.readAllBytes(outside));
    }

    @Test
    void rejectsWritesWhenTheConfiguredFreeSpaceFloorCannotBePreserved() throws Exception {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setFilesystemRoot(root.toString());
        properties.setFilesystemMinFreeBytes(Long.MAX_VALUE);
        storage = new FilesystemObjectStorage(properties);
        byte[] bytes = { 1, 2, 3 };

        assertThrows(
            ObjectStorageException.class,
            () -> storage.put(
                "workspaces/4/attachments/private.bin",
                source(bytes),
                "application/octet-stream",
                sha256(bytes)));
        assertFalse(storage.isReady());
    }

    @Test
    void refusesDeletionUntilAnActiveReaderCloses() throws Exception {
        byte[] bytes = "slow reader".getBytes(StandardCharsets.UTF_8);
        String key = "workspaces/17/attachments/slow.pdf";
        storage.put(key, source(bytes), "application/pdf", sha256(bytes));
        StoredObject open = storage.get(key);

        assertThrows(ObjectStorageException.class, () -> storage.delete(key));
        assertArrayEquals(bytes, open.inputStream().readAllBytes());

        open.close();
        storage.delete(key);
        assertThrows(ObjectStorageNotFoundException.class, () -> storage.get(key));
    }

    @Test
    void prunesEmptyEntityDirectoriesWithoutRemovingTheCategoryRoot() throws Exception {
        byte[] bytes = {1, 2, 3};
        String key = "workspaces/17/person-images/23/photo.png";
        Path entityDirectory = root.resolve("workspaces/17/person-images/23");
        Path categoryDirectory = entityDirectory.getParent();
        storage.put(key, source(bytes), "image/png", sha256(bytes));

        storage.delete(key);

        assertFalse(Files.exists(entityDirectory));
        assertTrue(Files.isDirectory(categoryDirectory));
    }

    private static UploadSource source(byte[] bytes) {
        return UploadSource.from("example.bin", "application/octet-stream", bytes);
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
}
