package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;

/**
 * Signature and pixel-bound validation for inline managed raster images.
 */
@Component
@RequiredArgsConstructor
public class ImageUploadValidator {
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final ObjectStorageProperties properties;
    private final UploadPolicy uploadPolicy;

    public ValidatedImage validate(UploadSource source) {
        uploadPolicy.validateLength(source.contentLength());
        byte[] bytes = read(source);
        ImageDimensions dimensions;
        String contentType;
        String extension;
        if (isPng(bytes)) {
            dimensions = pngDimensions(bytes);
            contentType = "image/png";
            extension = "png";
        } else if (isJpeg(bytes)) {
            dimensions = jpegDimensions(bytes);
            contentType = "image/jpeg";
            extension = "jpg";
        } else if (isWebp(bytes)) {
            dimensions = webpDimensions(bytes);
            contentType = "image/webp";
            extension = "webp";
        } else {
            throw new UnsupportedUploadMediaTypeException("Only JPEG, PNG, or WebP images are allowed");
        }
        validateDeclaredContentType(source.contentType(), contentType);
        validateDimensions(dimensions);
        return new ValidatedImage(contentType, extension);
    }

    private byte[] read(UploadSource source) {
        try (InputStream input = source.openStream()) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length != source.contentLength()) {
                throw new BadRequestException("Uploaded image length is invalid");
            }
            return bytes;
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Uploaded image could not be read");
        }
    }

    private void validateDeclaredContentType(String declared, String detected) {
        if (declared == null || declared.isBlank()) {
            return;
        }
        String normalized = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals(detected)) {
            throw new UnsupportedUploadMediaTypeException("Image content does not match its declared media type");
        }
    }

    private void validateDimensions(ImageDimensions dimensions) {
        if (dimensions.width() <= 0 || dimensions.height() <= 0
                || dimensions.width() > properties.getMaxImagePixels() / dimensions.height()) {
            throw new BadRequestException("Uploaded image dimensions are invalid or too large");
        }
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 33
            && Arrays.equals(Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE)
            && readInt32(bytes, 8) == 13
            && asciiEquals(bytes, 12, "IHDR");
    }

    private static ImageDimensions pngDimensions(byte[] bytes) {
        if (containsAscii(bytes, "acTL")) {
            throw new UnsupportedUploadMediaTypeException("Animated images are not supported");
        }
        return new ImageDimensions(readInt32(bytes, 16), readInt32(bytes, 20));
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 4 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8;
    }

    private static ImageDimensions jpegDimensions(byte[] bytes) {
        int offset = 2;
        while (offset + 3 < bytes.length) {
            while (offset < bytes.length && unsigned(bytes[offset]) != 0xff) {
                offset++;
            }
            while (offset < bytes.length && unsigned(bytes[offset]) == 0xff) {
                offset++;
            }
            if (offset >= bytes.length) {
                break;
            }
            int marker = unsigned(bytes[offset++]);
            if (marker == 0xd8 || marker == 0xd9) {
                continue;
            }
            if (marker == 0xda || offset + 1 >= bytes.length) {
                break;
            }
            int segmentLength = readUInt16(bytes, offset);
            if (segmentLength < 2 || offset + segmentLength > bytes.length) {
                break;
            }
            if (isStartOfFrame(marker) && segmentLength >= 7) {
                return new ImageDimensions(
                    readUInt16(bytes, offset + 5),
                    readUInt16(bytes, offset + 3)
                );
            }
            offset += segmentLength;
        }
        throw new BadRequestException("JPEG dimensions could not be read");
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xc0 && marker <= 0xcf
            && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 20
            && asciiEquals(bytes, 0, "RIFF")
            && asciiEquals(bytes, 8, "WEBP")
            && Integer.toUnsignedLong(readInt32LittleEndian(bytes, 4)) + 8L <= bytes.length;
    }

    private static ImageDimensions webpDimensions(byte[] bytes) {
        if (bytes.length >= 30 && asciiEquals(bytes, 12, "VP8X")) {
            if ((unsigned(bytes[20]) & 0x02) != 0) {
                throw new UnsupportedUploadMediaTypeException("Animated images are not supported");
            }
            return new ImageDimensions(1 + readUInt24(bytes, 24), 1 + readUInt24(bytes, 27));
        }
        if (asciiEquals(bytes, 12, "VP8L") && bytes.length >= 25 && unsigned(bytes[20]) == 0x2f) {
            int b1 = unsigned(bytes[21]);
            int b2 = unsigned(bytes[22]);
            int b3 = unsigned(bytes[23]);
            int b4 = unsigned(bytes[24]);
            int width = 1 + b1 + ((b2 & 0x3f) << 8);
            int height = 1 + ((b2 & 0xc0) >> 6) + (b3 << 2) + ((b4 & 0x0f) << 10);
            return new ImageDimensions(width, height);
        }
        if (asciiEquals(bytes, 12, "VP8 ") && bytes.length >= 30
                && unsigned(bytes[23]) == 0x9d && unsigned(bytes[24]) == 0x01 && unsigned(bytes[25]) == 0x2a) {
            int width = readUInt16LittleEndian(bytes, 26) & 0x3fff;
            int height = readUInt16LittleEndian(bytes, 28) & 0x3fff;
            return new ImageDimensions(width, height);
        }
        throw new BadRequestException("WebP dimensions could not be read");
    }

    private static int readInt32(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 24)
            | (unsigned(bytes[offset + 1]) << 16)
            | (unsigned(bytes[offset + 2]) << 8)
            | unsigned(bytes[offset + 3]);
    }

    private static int readUInt24(byte[] bytes, int offset) {
        return unsigned(bytes[offset])
            | (unsigned(bytes[offset + 1]) << 8)
            | (unsigned(bytes[offset + 2]) << 16);
    }

    private static int readUInt16(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 8) | unsigned(bytes[offset + 1]);
    }

    private static int readUInt16LittleEndian(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8);
    }

    private static int readInt32LittleEndian(byte[] bytes, int offset) {
        return unsigned(bytes[offset])
            | (unsigned(bytes[offset + 1]) << 8)
            | (unsigned(bytes[offset + 2]) << 16)
            | (unsigned(bytes[offset + 3]) << 24);
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        if (offset < 0 || offset + expected.length > bytes.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAscii(byte[] bytes, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int offset = 0; offset <= bytes.length - expected.length; offset++) {
            for (int index = 0; index < expected.length; index++) {
                if (bytes[offset + index] != expected[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Trusted detected image metadata.
     *
     * @param contentType detected media type
     * @param extension canonical extension without a leading dot
     */
    public record ValidatedImage(String contentType, String extension) {}

    private record ImageDimensions(int width, int height) {}
}
