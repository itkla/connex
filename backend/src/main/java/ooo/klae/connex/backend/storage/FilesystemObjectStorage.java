package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Persistent private filesystem object storage.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.object-storage",
    name = "provider",
    havingValue = "filesystem",
    matchIfMissing = true
)
public class FilesystemObjectStorage implements ObjectStorage {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");

    private final ObjectStorageProperties properties;
    private final AtomicLong reservedBytes = new AtomicLong();

    @Override
    public void put(String key, UploadSource source, String contentType, byte[] sha256) {
        Path target = resolve(key);
        Path temporary = null;
        long reservation = source.contentLength();
        boolean capacityReserved = false;
        try {
            ensurePrivateDirectory(target.getParent());
            reserveCapacity(target.getParent(), reservation);
            capacityReserved = true;
            temporary = Files.createTempFile(target.getParent(), ".connex-object-", ".tmp");
            restrictFile(temporary);
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
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public StoredObject get(String key) {
        Path target = resolve(key);
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
            return new StoredObject(Channels.newInputStream(channel), channel.size());
        } catch (ObjectStorageNotFoundException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException("Filesystem object read failed", exception);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObjectStorageException("Managed object path is not a regular file");
            }
            Files.delete(target);
            forceDirectory(target.getParent());
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException("Filesystem object deletion failed", exception);
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
                } catch (IOException ignored) {
                }
            }
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
}
