package ooo.klae.connex.backend.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Persistent private filesystem object storage.
 */
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(
    prefix = "connex.object-storage",
    name = "provider",
    havingValue = "filesystem",
    matchIfMissing = true
)
public class FilesystemObjectStorage implements ObjectStorage, ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FilesystemObjectStorage.class);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");

    private final ObjectStorageProperties properties;
    private final AtomicLong reservedBytes = new AtomicLong();
    private final ConcurrentHashMap<Path, PathLease> pathLeases = new ConcurrentHashMap<>();
    private final Set<Path> activeTemporaryFiles = ConcurrentHashMap.newKeySet();
    private final Object directoryMutationLock = new Object();

    @Override
    public void put(String key, UploadSource source, String contentType, byte[] sha256) {
        Path target = resolve(key);
        PathLease lease = retain(target);
        if (!lease.lock.writeLock().tryLock()) {
            release(target, lease);
            throw new ObjectStorageException("Managed object is currently being read");
        }
        Path temporary = null;
        long reservation = source.contentLength();
        boolean capacityReserved = false;
        try {
            synchronized (directoryMutationLock) {
                ensurePrivateDirectory(target.getParent());
                reserveCapacity(target.getParent(), reservation);
                capacityReserved = true;
                temporary = Files.createTempFile(target.getParent(), ".connex-object-", ".tmp");
                restrictFile(temporary);
                activeTemporaryFiles.add(temporary);
            }
            MessageDigest digest = sha256();
            long copied;
            try (InputStream input = new DigestInputStream(source.openStream(), digest);
                    FileChannel channel = FileChannel.open(
                        temporary,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                    OutputStream output = Channels.newOutputStream(channel)) {
                copied = input.transferTo(output);
                output.flush();
                channel.force(true);
            }
            if (copied != source.contentLength() || !MessageDigest.isEqual(digest.digest(), sha256)) {
                throw new ObjectStorageException("Stored object integrity check failed");
            }
            moveAtomically(temporary, target);
            forceDirectory(target.getParent());
            temporary = null;
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException("Filesystem object write failed", exception);
        } finally {
            if (capacityReserved) {
                reservedBytes.addAndGet(-reservation);
            }
            if (temporary != null) {
                activeTemporaryFiles.remove(temporary);
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    log.warn("Filesystem object write left a temporary file for scheduled reconciliation");
                }
            }
            lease.lock.writeLock().unlock();
            release(target, lease);
        }
    }

    @Override
    public StoredObject get(String key) {
        Path target = resolve(key);
        PathLease lease = retain(target);
        if (!lease.lock.readLock().tryLock()) {
            release(target, lease);
            throw new ObjectStorageException("Managed object is currently changing");
        }
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObjectStorageNotFoundException("Managed object was not found");
            }
            SeekableByteChannel channel = Files.newByteChannel(
                target,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                channel.close();
                throw new ObjectStorageNotFoundException("Managed object was not found");
            }
            long contentLength = channel.size();
            InputStream stream = new FilesystemReadStream(
                Channels.newInputStream(channel), target, lease);
            return new StoredObject(stream, contentLength);
        } catch (ObjectStorageNotFoundException exception) {
            lease.lock.readLock().unlock();
            release(target, lease);
            throw exception;
        } catch (IOException exception) {
            lease.lock.readLock().unlock();
            release(target, lease);
            throw new ObjectStorageException("Filesystem object read failed", exception);
        } catch (RuntimeException exception) {
            lease.lock.readLock().unlock();
            release(target, lease);
            throw exception;
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        PathLease lease = retain(target);
        if (!lease.lock.writeLock().tryLock()) {
            release(target, lease);
            throw new ObjectStorageException("Managed object is currently being read");
        }
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                synchronized (directoryMutationLock) {
                    pruneEmptyEntityDirectories(key, target.getParent());
                }
                return;
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObjectStorageException("Managed object path is not a regular file");
            }
            Files.delete(target);
            forceDirectory(target.getParent());
            synchronized (directoryMutationLock) {
                pruneEmptyEntityDirectories(key, target.getParent());
            }
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException("Filesystem object deletion failed", exception);
        } finally {
            lease.lock.writeLock().unlock();
            release(target, lease);
        }
    }

    @Override
    public boolean isReady() {
        Path probe = null;
        try {
            Path root = root();
            ensurePrivateDirectory(root);
            probe = Files.createTempFile(root, ".connex-readiness-", ".tmp");
            restrictFile(probe);
            return Files.isWritable(probe) && hasRequiredFreeCapacity(root);
        } catch (IOException | RuntimeException exception) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException exception) {
                    log.warn("Filesystem object readiness probe cleanup failed");
                }
            }
        }
    }

    @Override
    public void run(ApplicationArguments arguments) {
        reconcileTemporaryFilesAtStartup();
    }

    void reconcileTemporaryFilesAtStartup() {
        reconcileTemporaryFiles();
    }

    @Scheduled(
        fixedDelayString = "${connex.object-storage.filesystem-temp-cleanup-delay-ms:60000}",
        initialDelayString = "${connex.object-storage.filesystem-temp-cleanup-delay-ms:60000}")
    void reconcileTemporaryFilesOnSchedule() {
        reconcileTemporaryFiles();
    }

    void reconcileTemporaryFiles() {
        try {
            reconcileTemporaryFilesWithinRoot();
        } catch (RuntimeException exception) {
            log.warn("Filesystem object temporary-file cleanup failed before completion");
        }
    }

    private void reconcileTemporaryFilesWithinRoot() {
        Path storageRoot = root();
        if (!Files.exists(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(storageRoot)
                || !Files.isDirectory(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("Filesystem object temporary-file cleanup refused an unsafe storage root");
            return;
        }
        FileTime cutoff = FileTime.from(
            java.time.Instant.now().minus(properties.getFilesystemTempRetention()));
        TemporaryFileCleanupVisitor visitor = new TemporaryFileCleanupVisitor(
            cutoff, activeTemporaryFiles);
        try {
            synchronized (directoryMutationLock) {
                Files.walkFileTree(storageRoot, visitor);
            }
        } catch (IOException exception) {
            visitor.recordFailure();
        }
        if (visitor.failures() > 0) {
            log.warn("Filesystem object temporary-file cleanup failed for {} entries",
                visitor.failures());
        }
    }

    private Path resolve(String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        Path root = root();
        Path resolved = root.resolve(validKey + ".object").normalize();
        if (!resolved.startsWith(root)) {
            throw new ObjectStorageException("Invalid managed object path");
        }
        return resolved;
    }

    private PathLease retain(Path target) {
        return pathLeases.compute(target, (ignored, current) -> {
            PathLease lease = current == null ? new PathLease() : current;
            lease.references += 1;
            return lease;
        });
    }

    private void release(Path target, PathLease lease) {
        pathLeases.computeIfPresent(target, (ignored, current) -> {
            if (current != lease) {
                return current;
            }
            current.references -= 1;
            return current.references == 0 ? null : current;
        });
    }

    private void pruneEmptyEntityDirectories(String key, Path directory) throws IOException {
        String[] segments = key.split("/");
        if (segments.length != 5
                || !"workspaces".equals(segments[0])
                || !("person-images".equals(segments[2])
                    || "company-images".equals(segments[2]))) {
            return;
        }
        Path categoryRoot = root()
            .resolve(segments[0])
            .resolve(segments[1])
            .resolve(segments[2]);
        Path current = directory;
        while (!current.equals(categoryRoot) && current.startsWith(categoryRoot)) {
            Path parent = current.getParent();
            try {
                Files.delete(current);
                forceDirectory(parent);
            } catch (DirectoryNotEmptyException exception) {
                return;
            } catch (NoSuchFileException exception) {
                current = parent;
                continue;
            }
            current = parent;
        }
    }

    private Path root() {
        return properties.filesystemRootPath();
    }

    private void reserveCapacity(Path directory, long bytes) throws IOException {
        while (true) {
            long currentReservation = reservedBytes.get();
            long updatedReservation;
            long requiredFreeBytes;
            try {
                updatedReservation = Math.addExact(currentReservation, bytes);
                requiredFreeBytes = Math.addExact(
                    updatedReservation,
                    properties.getFilesystemMinFreeBytes());
            } catch (ArithmeticException exception) {
                throw new ObjectStorageException("Filesystem object capacity is unavailable", exception);
            }
            if (Files.getFileStore(directory).getUsableSpace() < requiredFreeBytes) {
                throw new ObjectStorageException("Filesystem object capacity is unavailable");
            }
            if (reservedBytes.compareAndSet(currentReservation, updatedReservation)) {
                return;
            }
        }
    }

    private boolean hasRequiredFreeCapacity(Path directory) throws IOException {
        long usableBytes = Files.getFileStore(directory).getUsableSpace();
        long currentReservation = reservedBytes.get();
        return currentReservation <= usableBytes
            && properties.getFilesystemMinFreeBytes() <= usableBytes - currentReservation;
    }

    private void ensurePrivateDirectory(Path directory) throws IOException {
        Path storageRoot = root();
        if (!directory.startsWith(storageRoot)) {
            throw new IOException("Object storage directory is outside the configured root");
        }
        if (Files.isSymbolicLink(storageRoot)) {
            throw new IOException("Object storage root must not be a symbolic link");
        }
        Path existing = nearestExistingParent(storageRoot);
        boolean posix = Files.getFileStore(existing).supportsFileAttributeView("posix");
        if (posix) {
            FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS);
            Files.createDirectories(storageRoot, permissions);
        } else {
            Files.createDirectories(storageRoot);
        }
        requireRealDirectory(storageRoot);
        restrictDirectory(storageRoot, posix);

        Path current = storageRoot;
        for (Path segment : storageRoot.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireRealDirectory(current);
            } else if (posix) {
                Files.createDirectory(
                    current,
                    PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            } else {
                Files.createDirectory(current);
            }
            restrictDirectory(current, posix);
        }
    }

    private static void restrictFile(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        }
    }

    private static Path nearestExistingParent(Path path) throws IOException {
        Path current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current == null || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Object storage parent directory is unavailable");
        }
        return current;
    }

    private static void requireRealDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Object storage path must contain only real directories");
        }
    }

    private static void restrictDirectory(Path directory, boolean posix) throws IOException {
        if (posix) {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private final class FilesystemReadStream extends FilterInputStream {
        private final Path target;
        private final PathLease lease;
        private final AtomicBoolean closed = new AtomicBoolean();

        private FilesystemReadStream(InputStream input, Path target, PathLease lease) {
            super(input);
            this.target = target;
            this.lease = lease;
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                super.close();
            } finally {
                lease.lock.readLock().unlock();
                release(target, lease);
            }
        }
    }

    private static final class PathLease {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private int references;
    }

    private static final class TemporaryFileCleanupVisitor
            extends java.nio.file.SimpleFileVisitor<Path> {
        private final FileTime cutoff;
        private final Set<Path> activeTemporaryFiles;
        private int failures;

        private TemporaryFileCleanupVisitor(
                FileTime cutoff,
                Set<Path> activeTemporaryFiles) {
            this.cutoff = cutoff;
            this.activeTemporaryFiles = activeTemporaryFiles;
        }

        @Override
        public java.nio.file.FileVisitResult visitFile(
                Path file,
                BasicFileAttributes attributes) {
            if (!attributes.isRegularFile()
                    || attributes.isSymbolicLink()
                    || attributes.lastModifiedTime().compareTo(cutoff) > 0
                    || activeTemporaryFiles.contains(file)
                    || !temporaryName(file)) {
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException exception) {
                failures += 1;
            }
            return java.nio.file.FileVisitResult.CONTINUE;
        }

        @Override
        public java.nio.file.FileVisitResult visitFileFailed(
                Path file,
                IOException exception) {
            failures += 1;
            return java.nio.file.FileVisitResult.CONTINUE;
        }

        @Override
        public java.nio.file.FileVisitResult postVisitDirectory(
                Path directory,
                IOException exception) {
            if (exception != null) {
                failures += 1;
            }
            return java.nio.file.FileVisitResult.CONTINUE;
        }

        private void recordFailure() {
            failures += 1;
        }

        private int failures() {
            return failures;
        }

        private static boolean temporaryName(Path file) {
            String name = file.getFileName().toString();
            return name.startsWith(".connex-object-") && name.endsWith(".tmp");
        }
    }
}
