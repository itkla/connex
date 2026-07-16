package ooo.klae.connex.backend.businesscard;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Unicode normalization shared by OCR extraction and exact company matching.
 */
public final class BusinessCardTextNormalizer {
    private BusinessCardTextNormalizer() {
    }

    public static String text(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u3000', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String companyKey(String value) {
        return text(value).toLowerCase(Locale.ROOT);
    }
}
