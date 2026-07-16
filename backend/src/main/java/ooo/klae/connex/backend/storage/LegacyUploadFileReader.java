package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Resolves only known app-relative legacy paths beneath the configured local upload root.
 */
@Component
@RequiredArgsConstructor
class LegacyUploadFileReader {
    private static final Pattern SAFE_SEGMENT = Pattern.compile(
        "[A-Za-z0-9_-][A-Za-z0-9._-]{0,255}");
    private static final Pattern SAFE_ENTITY_TYPE = Pattern.compile("[a-z0-9_-]{1,64}");

    private final ObjectStorageProperties properties;

    void validateConfiguration() {
        Path legacyRoot = requireRoot();
        if (properties.getProvider() != ObjectStorageProperties.Provider.FILESYSTEM) {
            return;
        }
        Path objectRoot = properties.filesystemRootPath();
        try {
            requireNoSymbolicLinks(objectRoot);
            if (Files.exists(objectRoot, LinkOption.NOFOLLOW_LINKS)) {
                objectRoot = objectRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Private object storage root is invalid", exception);
        }
        if (legacyRoot.startsWith(objectRoot) || objectRoot.startsWith(legacyRoot)) {
            throw new IllegalStateException(
                "Legacy uploads and private object storage roots must not overlap");
        }
    }

    ResolvedLegacyUpload read(String url, String requiredPrefix) {
        String relative = requireRelativePath(url, requiredPrefix);
        Path root = requireRoot();
        try {
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                throw new IllegalStateException("Legacy upload path escapes its configured root");
            }
            requireNoSymbolicLinks(candidate);
            Path source = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!source.startsWith(root) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Legacy upload source is invalid");
            }
            try (SeekableByteChannel channel = Files.newByteChannel(
                    source,
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
                    InputStream input = Channels.newInputStream(channel)) {
                long length = channel.size();
                if (length <= 0 || length > properties.getMaxUploadBytes()) {
                    throw new IllegalStateException(
                        "Legacy upload source exceeds the configured bounds");
                }
                byte[] content = input.readNBytes(Math.toIntExact(length) + 1);
                if (content.length != length || input.read() != -1) {
                    throw new IllegalStateException(
                        "Legacy upload source changed while it was read");
                }
                return new ResolvedLegacyUpload(source.getFileName().toString(), content);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Legacy upload source could not be read", exception);
        }
    }

    void validateOwnership(LegacyUploadRecord record, String requiredPrefix) {
        if (record == null || record.getId() <= 0 || record.getUrl() == null) {
            throw new IllegalStateException("Legacy upload owner metadata is invalid");
        }
        String expectedPathPrefix = switch (requiredPrefix) {
            case "/attachments/" -> attachmentOwnerPrefix(record);
            case "/contact-pictures/" -> "/contact-pictures/contact-" + record.getId() + "-";
            case "/company-logos/" -> "/company-logos/company-" + record.getId() + "-";
            case "/profile-pictures/" -> "/profile-pictures/user-" + record.getId() + "-";
            default -> throw new IllegalStateException("Legacy upload prefix is invalid");
        };
        if (!record.getUrl().startsWith(expectedPathPrefix)) {
            throw new IllegalStateException("Legacy upload reference does not match its owner");
        }
    }

    private static String attachmentOwnerPrefix(LegacyUploadRecord record) {
        String entityType = record.getEntityType();
        Integer entityId = record.getEntityId();
        if (entityType == null
                || !SAFE_ENTITY_TYPE.matcher(entityType).matches()
                || entityId == null
                || entityId <= 0) {
            throw new IllegalStateException("Legacy attachment owner metadata is invalid");
        }
        return "/attachments/" + entityType + "/" + entityType + "-" + entityId + "-";
    }

    private Path requireRoot() {
        Path configuredRoot = properties.getLegacyMigration().uploadsRootPath();
        try {
            requireNoSymbolicLinks(configuredRoot);
            Path root = configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Legacy upload root is invalid");
            }
            return root;
        } catch (IOException exception) {
            throw new IllegalStateException("Legacy upload root is invalid", exception);
        }
    }

    private static String requireRelativePath(String url, String requiredPrefix) {
        int expectedSegments = switch (requiredPrefix) {
            case "/attachments/" -> 2;
            case "/contact-pictures/", "/company-logos/", "/profile-pictures/" -> 1;
            default -> throw new IllegalStateException("Legacy upload prefix is invalid");
        };
        if (url == null
                || !url.startsWith(requiredPrefix)
                || url.length() <= requiredPrefix.length()
                || url.indexOf('\\') >= 0
                || url.indexOf('%') >= 0
                || url.indexOf('?') >= 0
                || url.indexOf('#') >= 0
                || url.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new IllegalStateException("Legacy upload reference is invalid");
        }
        String suffix = url.substring(requiredPrefix.length());
        String[] segments = suffix.split("/", -1);
        if (segments.length != expectedSegments) {
            throw new IllegalStateException("Legacy upload reference is invalid");
        }
        for (String segment : segments) {
            if (".".equals(segment)
                    || "..".equals(segment)
                    || !SAFE_SEGMENT.matcher(segment).matches()) {
                throw new IllegalStateException("Legacy upload reference is invalid");
            }
        }
        return requiredPrefix.substring(1) + suffix;
    }

    private static void requireNoSymbolicLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path segment : absolute) {
            current = current == null ? segment : current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are not allowed in legacy upload paths");
            }
        }
    }
}
