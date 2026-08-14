package ooo.klae.connex.backend.ai.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.storage.ImageUploadValidator;
import ooo.klae.connex.backend.storage.UploadContentInspector;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadPolicy;
import ooo.klae.connex.backend.storage.UploadPolicy.ValidatedUpload;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadSource;

/** Validates the deliberately narrow text and image boundary for assistant context files. */
@Component
@RequiredArgsConstructor
public class AiChatAttachmentPolicy {
    public static final int MAX_ATTACHMENTS = 10;
    public static final int MAX_TEXT_BYTES = 256_000;
    public static final int MAX_PROMPT_TEXT_CHARS = 32_000;
    public static final int MAX_TOTAL_PROMPT_CHARS = 64_000;

    private final UploadPolicy uploadPolicy;
    private final UploadContentInspector uploadContentInspector;
    private final ImageUploadValidator imageUploadValidator;

    /** Validates and canonicalizes one authoritative artifact before managed-object storage. */
    public InspectedUpload prepare(UploadSource source) {
        ValidatedUpload generic = uploadPolicy.validate(UploadPurpose.ASSISTANT_CONTEXT, source);
        boolean imageUpload = switch (generic.format()) {
            case JPEG, PNG, WEBP -> true;
            default -> false;
        };
        if (!imageUpload && source.contentLength() > MAX_TEXT_BYTES) {
            throw new RequestBodyTooLargeException(MAX_TEXT_BYTES);
        }
        InspectedUpload inspected = uploadContentInspector.inspect(
            UploadPurpose.ASSISTANT_CONTEXT, source);
        if (!imageUpload) {
            if (inspected.contentLength() > MAX_TEXT_BYTES) {
                throw new RequestBodyTooLargeException(MAX_TEXT_BYTES);
            }
            decodeText(inspected.content());
        }
        return inspected;
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
            return imageUploadValidator.validateStoredForAi(
                    UploadSource.from(fileName, "image/jpeg", bytes)).toInputImage();
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Assistant attachment could not be read");
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

}
