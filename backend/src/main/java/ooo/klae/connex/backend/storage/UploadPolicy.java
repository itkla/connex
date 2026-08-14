package ooo.klae.connex.backend.storage;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;

/**
 * Default-deny boundary for managed upload metadata and purpose-specific formats.
 *
 * <p>The shared CRM attachment set is deliberately limited to bounded raster images, PDF,
 * macro-free OOXML and OpenDocument files, UTF-8 text, and CSV. Those formats cover the normal
 * exchange documents used by a relationship CRM without admitting executables, active web
 * content, legacy binary or macro-enabled Office documents, or archives. ZIP is intentionally
 * excluded even though the admitted office formats use ZIP internally; their package structure
 * must identify a specific document format before they are accepted.
 *
 * <p>This fixed ceiling applies before provider selection and does not depend on the deployment
 * profile, so SaaS, silo, and on-prem installations enforce the same policy. There is no operator
 * setting that can widen it.
 */
@Component
@RequiredArgsConstructor
public class UploadPolicy {
    private static final Pattern CONTENT_TYPE =
        Pattern.compile("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$");
    private static final Pattern EXTENSION = Pattern.compile("^[a-z0-9]{1,10}$");
    private static final Map<UploadPurpose, Set<UploadFormat>> FORMATS_BY_PURPOSE = formatsByPurpose();

    private final ObjectStorageProperties properties;

    /**
     * Validates untrusted upload metadata against the fixed format set for one purpose.
     *
     * @param purpose server-selected upload purpose
     * @param source untrusted upload source
     * @return sanitized metadata and the expected real format
     */
    public ValidatedUpload validate(UploadPurpose purpose, UploadSource source) {
        validateLength(source.contentLength());
        String fileName = safeFileName(source.fileName());
        String contentType = declaredContentType(source.contentType());
        String extension = extension(fileName);
        UploadFormat format = formatFor(purpose, extension, contentType);
        return new ValidatedUpload(fileName, contentType, extension, format);
    }

    ValidatedUpload validateLegacyAttachment(UploadSource source, UploadFormat format) {
        validateLength(source.contentLength());
        if (!FORMATS_BY_PURPOSE.get(UploadPurpose.ATTACHMENT).contains(format)) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String fileName = replaceExtension(safeFileName(source.fileName()), format.canonicalExtension());
        return new ValidatedUpload(
            fileName,
            format.canonicalContentType(),
            format.canonicalExtension(),
            format);
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
        if (value == null || value.isBlank()) {
            return "application/octet-stream";
        }
        String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= 255 && CONTENT_TYPE.matcher(normalized).matches()
            ? normalized
            : "application/octet-stream";
    }

    private static UploadFormat formatFor(
            UploadPurpose purpose,
            String extension,
            String contentType) {
        Set<UploadFormat> formats = FORMATS_BY_PURPOSE.get(purpose);
        if (formats == null) {
            throw new IllegalArgumentException("Unknown upload purpose");
        }
        return formats.stream()
            .filter(format -> format.extensions().contains(extension))
            .filter(format -> format.contentTypes().contains(contentType))
            .findFirst()
            .orElseThrow(UnsupportedUploadMediaTypeException::unsupported);
    }

    private static String safeFileName(String value) {
        String candidate = value == null ? "" : value;
        int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        if (slash >= 0) {
            candidate = candidate.substring(slash + 1);
        }
        if (candidate.isEmpty()
                || hasUnsafeCodePoint(candidate)
                || hasUnsafeEnding(candidate)
                || candidate.length() > 255) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String normalized = Normalizer.normalize(candidate, Normalizer.Form.NFC);
        if (normalized.isBlank()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return normalized;
    }

    private static boolean hasUnsafeCodePoint(String value) {
        return value.codePoints().anyMatch(codePoint -> {
            int type = Character.getType(codePoint);
            return type == Character.CONTROL || type == Character.FORMAT;
        });
    }

    private static boolean hasUnsafeEnding(String value) {
        int last = value.codePointBefore(value.length());
        return last == '.' || Character.isWhitespace(last) || Character.isSpaceChar(last);
    }

    private static String declaredContentType(String value) {
        if (value == null || value.isBlank()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !CONTENT_TYPE.matcher(normalized).matches()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return normalized;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSION.matcher(extension).matches()) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        return extension;
    }

    private static String replaceExtension(String fileName, String extension) {
        int dot = fileName.lastIndexOf('.');
        String base = dot <= 0 ? fileName : fileName.substring(0, dot);
        return base + "." + extension;
    }

    private static Map<UploadPurpose, Set<UploadFormat>> formatsByPurpose() {
        Set<UploadFormat> rasterImages = EnumSet.of(
            UploadFormat.JPEG,
            UploadFormat.PNG,
            UploadFormat.GIF,
            UploadFormat.WEBP);
        Set<UploadFormat> strictRasterImages = EnumSet.of(
            UploadFormat.JPEG,
            UploadFormat.PNG,
            UploadFormat.WEBP);
        Set<UploadFormat> attachments = EnumSet.copyOf(rasterImages);
        attachments.addAll(EnumSet.of(
            UploadFormat.PDF,
            UploadFormat.DOCX,
            UploadFormat.XLSX,
            UploadFormat.PPTX,
            UploadFormat.ODT,
            UploadFormat.ODS,
            UploadFormat.ODP,
            UploadFormat.TEXT,
            UploadFormat.CSV));
        Set<UploadFormat> assistant = EnumSet.copyOf(strictRasterImages);
        assistant.addAll(EnumSet.of(
            UploadFormat.TEXT,
            UploadFormat.CSV,
            UploadFormat.MARKDOWN,
            UploadFormat.JSON));
        Map<UploadPurpose, Set<UploadFormat>> result = new EnumMap<>(UploadPurpose.class);
        result.put(UploadPurpose.ATTACHMENT, Set.copyOf(attachments));
        result.put(UploadPurpose.INLINE_IMAGE, Set.copyOf(rasterImages));
        result.put(UploadPurpose.ASSISTANT_CONTEXT, Set.copyOf(assistant));
        result.put(UploadPurpose.PROFILE_IMAGE, Set.copyOf(strictRasterImages));
        result.put(UploadPurpose.BUSINESS_CARD_IMAGE, Set.copyOf(strictRasterImages));
        result.put(UploadPurpose.CSV_IMPORT_SOURCE, Set.of(UploadFormat.CSV));
        return Map.copyOf(result);
    }

    /** Server-authoritative reason for accepting an upload. */
    public enum UploadPurpose {
        ATTACHMENT,
        INLINE_IMAGE,
        ASSISTANT_CONTEXT,
        PROFILE_IMAGE,
        BUSINESS_CARD_IMAGE,
        CSV_IMPORT_SOURCE
    }

    /** Closed set of inert business formats admitted by at least one upload purpose. */
    public enum UploadFormat {
        JPEG("jpg", "image/jpeg", Set.of("jpg", "jpeg"), Set.of("image/jpeg", "image/jpg")),
        PNG("png", "image/png", Set.of("png"), Set.of("image/png")),
        GIF("gif", "image/gif", Set.of("gif"), Set.of("image/gif")),
        WEBP("webp", "image/webp", Set.of("webp"), Set.of("image/webp")),
        PDF("pdf", "application/pdf", Set.of("pdf"), Set.of("application/pdf")),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx"), Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of("xlsx"), Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
        PPTX(
            "pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            Set.of("pptx"), Set.of(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation")),
        ODT(
            "odt", "application/vnd.oasis.opendocument.text",
            Set.of("odt"), Set.of("application/vnd.oasis.opendocument.text")),
        ODS(
            "ods", "application/vnd.oasis.opendocument.spreadsheet",
            Set.of("ods"), Set.of("application/vnd.oasis.opendocument.spreadsheet")),
        ODP(
            "odp", "application/vnd.oasis.opendocument.presentation",
            Set.of("odp"), Set.of("application/vnd.oasis.opendocument.presentation")),
        TEXT("txt", "text/plain", Set.of("txt"), Set.of("text/plain")),
        CSV("csv", "text/csv", Set.of("csv"), Set.of("text/csv")),
        MARKDOWN("md", "text/markdown", Set.of("md", "markdown"), Set.of("text/markdown")),
        JSON("json", "application/json", Set.of("json"), Set.of("application/json"));

        private final String canonicalExtension;
        private final String canonicalContentType;
        private final Set<String> extensions;
        private final Set<String> contentTypes;

        UploadFormat(
                String canonicalExtension,
                String canonicalContentType,
                Set<String> extensions,
                Set<String> contentTypes) {
            this.canonicalExtension = canonicalExtension;
            this.canonicalContentType = canonicalContentType;
            this.extensions = extensions;
            this.contentTypes = contentTypes;
        }

        public String canonicalExtension() {
            return canonicalExtension;
        }

        public String canonicalContentType() {
            return canonicalContentType;
        }

        public Set<String> extensions() {
            return extensions;
        }

        public Set<String> contentTypes() {
            return contentTypes;
        }
    }

    /**
     * Sanitized metadata used for persistence and later real-content inspection.
     *
     * @param fileName safe display file name
     * @param contentType normalized declared media type
     * @param extension normalized declared extension without a leading dot
     * @param format expected real format selected by the purpose allowlist
     */
    public record ValidatedUpload(
            String fileName,
            String contentType,
            String extension,
            UploadFormat format) {}
}
