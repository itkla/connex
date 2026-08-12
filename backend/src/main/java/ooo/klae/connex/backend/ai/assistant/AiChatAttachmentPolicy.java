package ooo.klae.connex.backend.ai.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;
import ooo.klae.connex.backend.storage.ImageUploadValidator;
import ooo.klae.connex.backend.storage.ImageUploadValidator.ValidatedAiImage;
import ooo.klae.connex.backend.storage.UploadPolicy;
import ooo.klae.connex.backend.storage.UploadPolicy.ValidatedUpload;
import ooo.klae.connex.backend.storage.UploadSource;

/** Validates the deliberately narrow text and image boundary for assistant context files. */
@Component
@RequiredArgsConstructor
public class AiChatAttachmentPolicy {
    public static final int MAX_ATTACHMENTS = 10;
    public static final int MAX_TEXT_BYTES = 256_000;
    public static final int MAX_PROMPT_TEXT_CHARS = 32_000;
    public static final int MAX_TOTAL_PROMPT_CHARS = 64_000;
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp");
    private static final Map<String, String> TEXT_TYPE_BY_EXTENSION = Map.of(
            "txt", "text/plain",
            "md", "text/markdown",
            "markdown", "text/markdown",
            "csv", "text/csv",
            "json", "application/json");
    private static final Map<String, Set<String>> TEXT_EXTENSIONS_BY_TYPE = Map.of(
            "text/plain", Set.of("", "txt"),
            "text/markdown", Set.of("", "md", "markdown"),
            "text/csv", Set.of("", "csv"),
            "application/json", Set.of("", "json"));

    private final UploadPolicy uploadPolicy;
    private final ImageUploadValidator imageUploadValidator;

    /** Validates and canonicalizes one upload before managed-object storage. */
    public UploadSource prepare(UploadSource source) {
        ValidatedUpload generic = uploadPolicy.validateGeneric(source);
        String extension = extension(generic.fileName());
        String contentType = generic.contentType();
        if (IMAGE_TYPES.contains(contentType)
                || ("application/octet-stream".equals(contentType)
                    && Set.of("jpg", "jpeg", "png", "webp").contains(extension))) {
            ValidatedAiImage image = imageUploadValidator.validateForAi(source);
            String fileName = replaceExtension(generic.fileName(), "jpg");
            return UploadSource.from(fileName, "image/jpeg", image.content());
        }
        String resolvedContentType = "application/octet-stream".equals(contentType)
                ? TEXT_TYPE_BY_EXTENSION.get(extension)
                : contentType;
        Set<String> allowedExtensions = TEXT_EXTENSIONS_BY_TYPE.get(resolvedContentType);
        if (resolvedContentType == null
                || allowedExtensions == null
                || !allowedExtensions.contains(extension)) {
            throw new UnsupportedUploadMediaTypeException(
                    "Ask Connex accepts TXT, Markdown, CSV, JSON, JPEG, PNG, or WebP files");
        }
        if (source.contentLength() > MAX_TEXT_BYTES) {
            throw new RequestBodyTooLargeException(MAX_TEXT_BYTES);
        }
        byte[] bytes = readExactly(source, MAX_TEXT_BYTES);
        decodeText(bytes);
        return UploadSource.from(generic.fileName(), resolvedContentType, bytes);
    }

    /** Reads and strictly decodes one bounded stored text attachment. */
    public String readText(InputStream input, long contentLength) {
        if (contentLength <= 0 || contentLength > MAX_TEXT_BYTES) {
            throw new BadRequestException("Assistant text attachment length is invalid");
        }
        int expected = Math.toIntExact(contentLength);
        try {
            byte[] bytes = input.readNBytes(expected + 1);
            if (bytes.length != expected || input.read() != -1) {
                throw new BadRequestException("Assistant text attachment length is invalid");
            }
            return decodeText(bytes);
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Assistant attachment could not be read");
        }
    }

    /** Revalidates one stored canonical image before provider egress. */
    public AiInputImage readImage(String fileName, InputStream input, long contentLength) {
        if (contentLength <= 0 || contentLength > AiInputImage.MAX_BYTES) {
            throw new BadRequestException("Assistant image attachment length is invalid");
        }
        int expected = Math.toIntExact(contentLength);
        try {
            byte[] bytes = input.readNBytes(expected + 1);
            if (bytes.length != expected || input.read() != -1) {
                throw new BadRequestException("Assistant image attachment length is invalid");
            }
            return imageUploadValidator.validateForAi(
                    UploadSource.from(fileName, "image/jpeg", bytes)).toInputImage();
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Assistant attachment could not be read");
        }
    }

    private static byte[] readExactly(UploadSource source, int limit) {
        int expected = Math.toIntExact(source.contentLength());
        try (InputStream input = source.openStream()) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length != expected || input.read() != -1) {
                throw new BadRequestException("Uploaded file length is invalid");
            }
            return bytes;
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Uploaded file could not be read");
        }
    }

    private static String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new BadRequestException("Assistant text attachments must be valid UTF-8");
        }
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String replaceExtension(String fileName, String extension) {
        int dot = fileName.lastIndexOf('.');
        String base = dot <= 0 ? fileName : fileName.substring(0, dot);
        return base + "." + extension;
    }

}
