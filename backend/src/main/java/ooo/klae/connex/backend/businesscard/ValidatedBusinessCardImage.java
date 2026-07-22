package ooo.klae.connex.backend.businesscard;

/**
 * Safely decoded business-card image ready for OCR or durable storage.
 *
 * @param content orientation-normalized, metadata-free image bytes
 * @param contentType normalized media type
 * @param extension safe filename extension
 * @param width decoded pixel width
 * @param height decoded pixel height
 */
public record ValidatedBusinessCardImage(
        byte[] content,
        String contentType,
        String extension,
        int width,
        int height) {
    public ValidatedBusinessCardImage {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
