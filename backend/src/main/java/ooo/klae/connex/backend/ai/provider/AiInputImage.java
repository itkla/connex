package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/**
 * One bounded, embedded image accepted by the provider seam.
 *
 * @param contentType allowlisted image media type
 * @param content validated image bytes
 * @param width decoded pixel width
 * @param height decoded pixel height
 */
public record AiInputImage(
        String contentType,
        byte[] content,
        int width,
        int height) {
    public static final int MAX_BYTES = 3_500_000;
    public static final int MAX_DIMENSION = 4_096;
    private static final String JPEG = "image/jpeg";

    public AiInputImage {
        if (!JPEG.equals(contentType)) {
            throw new IllegalArgumentException("AI input image must be a JPEG");
        }
        content = Objects.requireNonNull(content, "content").clone();
        if (content.length == 0 || content.length > MAX_BYTES) {
            throw new IllegalArgumentException("AI input image exceeds the supported byte bounds");
        }
        if (content.length < 3
                || Byte.toUnsignedInt(content[0]) != 0xff
                || Byte.toUnsignedInt(content[1]) != 0xd8
                || Byte.toUnsignedInt(content[2]) != 0xff) {
            throw new IllegalArgumentException("AI input image content is not a JPEG");
        }
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("AI input image exceeds the supported dimension bounds");
        }
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    public int size() {
        return content.length;
    }

    @Override
    public String toString() {
        return "AiInputImage[contentType=" + contentType
                + ", content=<redacted>, width=" + width + ", height=" + height + "]";
    }
}
