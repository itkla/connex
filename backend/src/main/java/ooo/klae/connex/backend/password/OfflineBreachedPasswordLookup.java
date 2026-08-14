package ooo.klae.connex.backend.password;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

/**
 * File-backed lookup for verified, sorted, fixed-width offline SHA-1 corpora.
 */
@Component
public class OfflineBreachedPasswordLookup implements BreachedPasswordLookup {
    private static final int HASH_CHARACTERS = 40;
    private static final int RECORD_BYTES = 41;
    static final int VALIDATION_RECORDS_PER_BUFFER = 4096;
    private static final int VALIDATION_BUFFER_BYTES = RECORD_BYTES * VALIDATION_RECORDS_PER_BUFFER;

    private final BreachedPasswordProperties properties;

    private volatile Path verifiedPath;
    private volatile FileChannel verifiedChannel;
    private volatile long verifiedSize;
    private volatile FileTime verifiedLastModified;
    private volatile Object verifiedFileKey;
    private volatile Object verifiedChangeTime;

    public OfflineBreachedPasswordLookup(BreachedPasswordProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public void validate() {
        String configured = normalize(properties.getOfflineFile());
        String expectedChecksum = normalize(properties.getOfflineSha256()).toUpperCase();
        if (configured.isEmpty() || !expectedChecksum.matches("[0-9A-F]{64}")) {
            throw new IllegalStateException(
                    "OFFLINE breached-password source requires a file and SHA-256 checksum");
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Offline breached-password file must be a regular non-symbolic file");
        }
        try {
            BasicFileAttributes initialAttributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object initialChangeTime = changeTime(path);
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            retainVerifiedChannel(
                    path, channel, initialAttributes, initialChangeTime, expectedChecksum);
        } catch (IOException exception) {
            throw new IllegalStateException("Offline breached-password file could not be verified");
        }
    }

    @Override
    public boolean isBreached(String sha1Hex) {
        if (sha1Hex == null || !sha1Hex.matches("[0-9A-F]{40}")) {
            throw unavailable();
        }
        Path path = verifiedPath;
        FileChannel channel = verifiedChannel;
        if (path == null || channel == null || !channel.isOpen()) {
            throw unavailable();
        }
        try {
            assertUnchanged(path);
            boolean breached = search(channel, sha1Hex);
            assertUnchanged(path);
            return breached;
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    @PreDestroy
    void close() {
        FileChannel channel = verifiedChannel;
        verifiedChannel = null;
        if (channel != null) {
            closeChannel(channel);
        }
    }

    private void retainVerifiedChannel(Path path, FileChannel channel,
            BasicFileAttributes initialAttributes, Object initialChangeTime,
            String expectedChecksum) throws IOException {
        try {
            long channelSize = channel.size();
            if (channelSize == 0 || channelSize % RECORD_BYTES != 0) {
                throw new IllegalStateException(
                        "Offline breached-password file must contain fixed-width SHA-1 records");
            }
            String actualChecksum = validateContentAndDigest(channel, channelSize);
            if (!MessageDigest.isEqual(
                    expectedChecksum.getBytes(StandardCharsets.US_ASCII),
                    actualChecksum.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Offline breached-password file checksum does not match");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object currentChangeTime = changeTime(path);
            if (channel.size() != channelSize
                    || channelSize != initialAttributes.size()
                    || initialAttributes.size() != attributes.size()
                    || !initialAttributes.lastModifiedTime().equals(attributes.lastModifiedTime())
                    || !Objects.equals(initialAttributes.fileKey(), attributes.fileKey())
                    || !Objects.equals(initialChangeTime, currentChangeTime)) {
                throw new IllegalStateException(
                        "Offline breached-password file changed during verification");
            }
            FileChannel previousChannel = verifiedChannel;
            verifiedPath = path;
            verifiedSize = channelSize;
            verifiedLastModified = attributes.lastModifiedTime();
            verifiedFileKey = attributes.fileKey();
            verifiedChangeTime = currentChangeTime;
            verifiedChannel = channel;
            if (previousChannel != null) {
                closeChannel(previousChannel);
            }
        } catch (IOException | RuntimeException exception) {
            try {
                channel.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private boolean search(FileChannel channel, String sha1Hex) throws IOException {
        long low = 0;
        long high = verifiedSize / RECORD_BYTES - 1;
        while (low <= high) {
            long middle = low + (high - low) / 2;
            String candidate = readHash(channel, middle * RECORD_BYTES);
            int comparison = candidate.compareTo(sha1Hex);
            if (comparison == 0) {
                return true;
            }
            if (comparison < 0) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return false;
    }

    private String validateContentAndDigest(FileChannel channel, long size) throws IOException {
        MessageDigest digest = sha256();
        byte[] previous = null;
        byte[] record = new byte[RECORD_BYTES];
        ByteBuffer buffer = ByteBuffer.allocate(VALIDATION_BUFFER_BYTES);
        for (long position = 0; position < size;) {
            int bytesToRead = (int) Math.min(buffer.capacity(), size - position);
            buffer.clear();
            buffer.limit(bytesToRead);
            readBuffer(channel, position, buffer);
            position += bytesToRead;
            buffer.flip();
            digest.update(buffer.asReadOnlyBuffer());
            while (buffer.hasRemaining()) {
                buffer.get(record);
                if (record[HASH_CHARACTERS] != '\n') {
                    throw new IllegalStateException(
                            "Offline breached-password file must use LF-delimited records");
                }
                if (!isUpperHex(record)
                        || previous != null && compareHashBytes(previous, record) >= 0) {
                    throw new IllegalStateException(
                            "Offline breached-password hashes must be uppercase, unique, and sorted");
                }
                if (previous == null) {
                    previous = new byte[HASH_CHARACTERS];
                }
                System.arraycopy(record, 0, previous, 0, HASH_CHARACTERS);
            }
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void readBuffer(FileChannel channel, long position, ByteBuffer buffer)
            throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + total);
            if (read <= 0) {
                throw unavailable();
            }
            total += read;
        }
    }

    private void assertUnchanged(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw unavailable();
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.size() != verifiedSize
                || !attributes.lastModifiedTime().equals(verifiedLastModified)
                || !Objects.equals(attributes.fileKey(), verifiedFileKey)
                || !Objects.equals(changeTime(path), verifiedChangeTime)) {
            throw unavailable();
        }
    }

    private static Object changeTime(Path path) throws IOException {
        try {
            return Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException exception) {
            return null;
        }
    }

    private static String readHash(FileChannel channel, long position) throws IOException {
        byte[] hash = new byte[HASH_CHARACTERS];
        readBytes(channel, position, hash);
        return new String(hash, StandardCharsets.US_ASCII);
    }

    private static void readBytes(FileChannel channel, long position, byte[] destination)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(destination);
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + total);
            if (read < 0) {
                throw unavailable();
            }
            if (read == 0) {
                throw unavailable();
            }
            total += read;
        }
    }

    private static void closeChannel(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException exception) {
            throw new IllegalStateException("Offline breached-password file could not be closed");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static boolean isUpperHex(byte[] record) {
        for (int index = 0; index < HASH_CHARACTERS; index++) {
            byte value = record[index];
            if (!((value >= '0' && value <= '9') || (value >= 'A' && value <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static int compareHashBytes(byte[] previous, byte[] current) {
        for (int index = 0; index < HASH_CHARACTERS; index++) {
            int comparison = Byte.compareUnsigned(previous[index], current[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static BreachedPasswordSourceUnavailableException unavailable() {
        return new BreachedPasswordSourceUnavailableException(
                BreachedPasswordUnavailableReason.OFFLINE_SOURCE);
    }
}
