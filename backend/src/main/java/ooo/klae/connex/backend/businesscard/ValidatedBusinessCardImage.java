package ooo.klae.connex.backend.businesscard;

/**
 * Safely decoded business-card image ready for OCR or durable storage.
 *
 * @param content original validated bytes
 * @param contentType signature-derived media type
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
}
