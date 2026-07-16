package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class FilesystemObjectStorageTest {
    @TempDir Path temporaryDirectory;

    private Path root;
    private FilesystemObjectStorage storage;
    private ObjectStorageProperties properties;

    @BeforeEach
    void setUp() {
        root = temporaryDirectory.resolve("private-objects");
        properties = new ObjectStorageProperties();
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
    void allowsStreamingThreadToCloseReadLeaseAcquiredByRequestThread() throws Exception {
        byte[] bytes = "cross-thread reader".getBytes(StandardCharsets.UTF_8);
        String key = "workspaces/17/attachments/streamed.pdf";
        storage.put(key, source(bytes), "application/pdf", sha256(bytes));
        StoredObject open = storage.get(key);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        assertThrows(ObjectStorageException.class, () -> storage.delete(key));
        Thread streamingThread = Thread.startVirtualThread(() -> {
            try (open) {
                assertArrayEquals(bytes, open.inputStream().readAllBytes());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        streamingThread.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(streamingThread.isAlive());
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
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

    @Test
    void startupReconciliationDeletesOnlyExpiredManagedTemporaryFiles() throws Exception {
        properties.setFilesystemTempRetention(Duration.ofMinutes(5));
        Path directory = root.resolve("workspaces/17/attachments");
        Files.createDirectories(directory);
        Path expired = Files.write(directory.resolve(".connex-object-expired.tmp"),
            new byte[] {1});
        Path fresh = Files.write(directory.resolve(".connex-object-fresh.tmp"),
            new byte[] {2});
        Path unrelated = Files.write(directory.resolve("other.tmp"), new byte[] {3});
        Files.setLastModifiedTime(expired, FileTime.from(Instant.now().minus(Duration.ofHours(1))));

        storage.reconcileTemporaryFilesAtStartup();

        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void reconciliationDoesNotFollowDirectoryOrFileSymbolicLinks() throws Exception {
        properties.setFilesystemTempRetention(Duration.ofSeconds(1));
        Files.createDirectories(root);
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        Path outsideTemporary = Files.write(
            outside.resolve(".connex-object-outside.tmp"), new byte[] {1});
        Files.setLastModifiedTime(
            outsideTemporary, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        Path linkedFile = root.resolve(".connex-object-link.tmp");
        try {
            Files.createSymbolicLink(root.resolve("linked-directory"), outside);
            Files.createSymbolicLink(linkedFile, outsideTemporary);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        storage.reconcileTemporaryFiles();

        assertTrue(Files.exists(outsideTemporary));
        assertTrue(Files.isSymbolicLink(linkedFile));
        assertTrue(Files.isSymbolicLink(root.resolve("linked-directory")));
    }

    @Test
    void reconciliationNeverDeletesAnActiveTemporaryWrite() throws Exception {
        properties.setFilesystemTempRetention(Duration.ofNanos(1));
        byte[] bytes = {1, 2, 3};
        CountDownLatch transferStarted = new CountDownLatch(1);
        CountDownLatch releaseTransfer = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        UploadSource source = new UploadSource(
            "active.bin",
            "application/octet-stream",
            bytes.length,
            () -> blockingInput(bytes, transferStarted, releaseTransfer));
        Thread writer = Thread.startVirtualThread(() -> {
            try {
                storage.put(
                    "workspaces/17/attachments/active.bin",
                    source,
                    "application/octet-stream",
                    sha256(bytes));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        try {
            assertTrue(transferStarted.await(5, TimeUnit.SECONDS));
            Path activeTemporary;
            try (var paths = Files.walk(root)) {
                activeTemporary = paths
                    .filter(path -> path.getFileName().toString().startsWith(".connex-object-"))
                    .findFirst()
                    .orElseThrow();
            }
            Files.setLastModifiedTime(
                activeTemporary,
                FileTime.from(Instant.now().minus(Duration.ofHours(1))));

            storage.reconcileTemporaryFiles();

            assertTrue(Files.exists(activeTemporary));
        } finally {
            releaseTransfer.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertFalse(writer.isAlive());
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertTrue(Files.exists(
            root.resolve("workspaces/17/attachments/active.bin.object")));
    }

    @Test
    void unsafeCleanupRootIsSurfacedWithoutFollowingIt(CapturedOutput output) throws Exception {
        Path outside = temporaryDirectory.resolve("outside-root");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(root, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        storage.reconcileTemporaryFiles();

        assertTrue(output.getAll().contains(
            "Filesystem object temporary-file cleanup refused an unsafe storage root"));
        assertTrue(Files.isDirectory(outside));
    }

    private static UploadSource source(byte[] bytes) {
        return UploadSource.from("example.bin", "application/octet-stream", bytes);
    }

    private static InputStream blockingInput(
            byte[] bytes,
            CountDownLatch transferStarted,
            CountDownLatch releaseTransfer) {
        return new InputStream() {
            private int offset;
            private boolean released;

            @Override
            public int read() throws IOException {
                awaitRelease();
                return offset < bytes.length ? Byte.toUnsignedInt(bytes[offset++]) : -1;
            }

            @Override
            public int read(byte[] target, int targetOffset, int length) throws IOException {
                awaitRelease();
                if (offset >= bytes.length) {
                    return -1;
                }
                int copied = Math.min(length, bytes.length - offset);
                System.arraycopy(bytes, offset, target, targetOffset, copied);
                offset += copied;
                return copied;
            }

            private void awaitRelease() throws IOException {
                if (released) {
                    return;
                }
                transferStarted.countDown();
                try {
                    if (!releaseTransfer.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release test upload");
                    }
                    released = true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to release test upload", exception);
                }
            }
        };
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
}
