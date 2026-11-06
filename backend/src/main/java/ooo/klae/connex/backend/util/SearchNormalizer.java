package ooo.klae.connex.backend.util;

import java.text.Normalizer;
import java.util.Locale;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

/** Search-only projections: never used as identity keys or duplicate-match authority. */
public final class SearchNormalizer {
    private SearchNormalizer() {
    }

    /** Folds compatibility width, kana and Unicode spacing while preserving punctuation and order. */
    public static String name(String raw) {
        if (raw == null || raw.length() > 2048) {
            return "";
        }
        String normalized = Normalizer.normalize(
            Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder result = new StringBuilder();
        normalized.codePoints().forEach(codePoint -> {
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                if ((codePoint >= 0x30a1 && codePoint <= 0x30f6)
                        || codePoint == 0x30fd || codePoint == 0x30fe) {
                    result.appendCodePoint(codePoint - 0x60);
                } else {
                    result.appendCodePoint(codePoint);
                }
            }
        });
        String key = Normalizer.normalize(result, Normalizer.Form.NFC);
        return key.codePointCount(0, key.length()) <= 1024 ? key : "";
    }

    /** Uses Japan for domestic numbers, like identity matching, and retains digit fragments. */
    public static String phone(String raw) {
        if (raw == null || raw.length() > 256) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder digits = new StringBuilder();
        for (int codePoint : normalized.codePoints().toArray()) {
            if (codePoint >= '0' && codePoint <= '9') {
                digits.appendCodePoint(codePoint);
            } else if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                    && "+()./\u30fb\u30fc-\u2010\u2011\u2012\u2013\u2014\u2212".indexOf(codePoint) < 0) {
                return "";
            }
        }
        if (digits.isEmpty()) {
            return "";
        }
        if (!normalized.stripLeading().startsWith("+") && digits.charAt(0) != '0') {
            return digits.toString();
        }
        PhoneNumberUtil phones = PhoneNumberUtil.getInstance();
        String parseable = normalized.startsWith("+81(0)")
            ? "+81" + normalized.substring(6) : normalized;
        try {
            var parsed = phones.parse(parseable, "JP");
            if (phones.isValidNumber(parsed) && !parsed.hasExtension()) {
                return phones.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164).substring(1);
            }
        } catch (NumberParseException exception) {
            return digits.toString();
        }
        return digits.toString();
    }

    /** Projects an already escaped contains pattern without treating literal wildcards as operators. */
    public static String namePattern(String pattern) {
        return containingOrNever(name(unescapeContains(pattern)));
    }

    /** Projects an already escaped phone contains pattern; non-phone queries cannot match every row. */
    public static String phonePattern(String pattern) {
        return containingOrNever(phone(unescapeContains(pattern)));
    }

    private static String containingOrNever(String key) {
        return key.isEmpty() ? null : LikePattern.containing(key);
    }

    private static String unescapeContains(String pattern) {
        if (pattern == null || pattern.length() < 2) {
            return "";
        }
        String value = pattern.substring(1, pattern.length() - 1);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' && index + 1 < value.length()) {
                current = value.charAt(++index);
            }
            result.append(current);
        }
        return result.toString();
    }
}
