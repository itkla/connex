package ooo.klae.connex.backend.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/** Canonical Unicode normalization shared by authored names and persisted duplicate keys. */
public final class CanonicalNameNormalizer {
    private static final int MAX_NAME_CODE_POINTS = 255;
    private static final int MAX_INPUT_LENGTH = 2_048;

    private CanonicalNameNormalizer() {
    }

    /**
     * Normalizes a human name without changing name order or punctuation.
     * @param raw authored or imported name
     * @return canonical name, or empty when the input is invalid
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.length() > MAX_INPUT_LENGTH) {
            return Optional.empty();
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder folded = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isUnicodeSpace(codePoint)) {
                pendingSpace = folded.length() > 0;
                continue;
            }
            if (isForbiddenCodePoint(codePoint)) {
                return Optional.empty();
            }
            if (pendingSpace) {
                folded.append(' ');
                pendingSpace = false;
            }
            appendHiraganaFold(folded, codePoint);
        }
        String result = Normalizer.normalize(
            folded.toString().toLowerCase(Locale.ROOT),
            Normalizer.Form.NFC);
        if (result.isEmpty()
                || result.codePointCount(0, result.length()) > MAX_NAME_CODE_POINTS) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    private static void appendHiraganaFold(StringBuilder target, int codePoint) {
        if (codePoint >= 0x30a1 && codePoint <= 0x30f6) {
            target.appendCodePoint(codePoint - 0x60);
        } else if (codePoint == 0x30fd || codePoint == 0x30fe) {
            target.appendCodePoint(codePoint - 0x60);
        } else if (codePoint >= 0x30f7 && codePoint <= 0x30fa) {
            int[] bases = {0x308f, 0x3090, 0x3091, 0x3092};
            target.appendCodePoint(bases[codePoint - 0x30f7]);
            target.appendCodePoint(0x3099);
        } else {
            target.appendCodePoint(codePoint);
        }
    }

    private static boolean isUnicodeSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isForbiddenCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
            || type == Character.FORMAT;
    }
}
