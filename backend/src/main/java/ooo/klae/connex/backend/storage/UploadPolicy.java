package ooo.klae.connex.backend.storage;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;

/**
 * Boundary policy for managed file names, media types, and upload sizes.
 */
@Component
@RequiredArgsConstructor
public class UploadPolicy {
    private static final Set<String> RENDERABLE_EXTENSIONS = Set.of(
        "html", "htm", "xhtml", "shtml", "svg", "svgz", "xml", "xsl", "xslt",
        "js", "mjs", "cjs", "htaccess"
    );
    private static final Set<String> RENDERABLE_CONTENT_TYPES = Set.of(
        "text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml",
        "text/javascript", "application/javascript", "application/ecmascript", "text/ecmascript"
    );
    private static final Pattern CONTENT_TYPE = Pattern.compile("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$");
    private static final Pattern EXTENSION = Pattern.compile("^[a-z0-9]{1,10}$");

    private final ObjectStorageProperties properties;

    public ValidatedUpload validateGeneric(UploadSource source) {
        validateLength(source.contentLength());
        String fileName = safeFileName(source.fileName());
        String contentType = safeContentType(source.contentType());
        String extension = extension(fileName);
        if (RENDERABLE_EXTENSIONS.contains(extension) || RENDERABLE_CONTENT_TYPES.contains(contentType)) {
            throw new UnsupportedUploadMediaTypeException("This file type cannot be uploaded as an attachment");
        }
        return new ValidatedUpload(fileName, contentType, extension);
    }

    public void validateLength(long length) {
        if (length <= 0) {
            throw new BadRequestException("Uploaded file must not be empty");
        }
        if (length > properties.getMaxUploadBytes()) {
            throw new RequestBodyTooLargeException(properties.getMaxUploadBytes());
        }
    }

    public String safeResponseFileName(String value) {
        return safeFileName(value);
    }

    public String safeResponseContentType(String value) {
        return safeContentType(value);
    }

    private static String safeFileName(String value) {
        String candidate = value == null ? "file" : value;
        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (slash >= 0) {
            candidate = candidate.substring(slash + 1);
        }
        candidate = candidate.replaceAll("[\\p{Cc}\\p{Cf}]", "_").strip();
        if (candidate.isBlank()) {
            candidate = "file";
        }
        if (candidate.length() > 255) {
            candidate = candidate.substring(0, 255);
        }
        return candidate;
    }

    private static String safeContentType(String value) {
        if (value == null || value.isBlank()) {
            return "application/octet-stream";
        }
        String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 255 && CONTENT_TYPE.matcher(normalized).matches()
            ? normalized
            : "application/octet-stream";
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSION.matcher(extension).matches() ? extension : "";
    }

    /**
     * Sanitized metadata used for persistence and response headers.
     *
     * @param fileName safe display file name
     * @param contentType normalized media type
     * @param extension safe optional extension without a leading dot
     */
    public record ValidatedUpload(String fileName, String contentType, String extension) {}
}
