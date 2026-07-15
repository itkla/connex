package ooo.klae.connex.backend.businesscard;

/**
 * One OCR line with confidence and a rectangular source-image box.
 *
 * @param text recognized text
 * @param confidence recognition confidence from zero to one
 * @param xMin left coordinate
 * @param yMin top coordinate
 * @param xMax right coordinate
 * @param yMax bottom coordinate
 */
public record OcrLine(
        String text,
        double confidence,
        int xMin,
        int yMin,
        int xMax,
        int yMax) {
    public int height() {
        return Math.max(0, yMax - yMin);
    }

    public int verticalCenter() {
        return yMin + height() / 2;
    }
}
