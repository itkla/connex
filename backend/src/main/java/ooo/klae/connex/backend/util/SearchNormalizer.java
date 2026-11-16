package ooo.klae.connex.backend.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

/** Search-only projections: never used as identity keys or duplicate-match authority. */
public final class SearchNormalizer {
    private static final Pattern PHONE_EXTENSION = Pattern.compile("(?i)(?:;ext=|ext\\.?|x|#|内線)[0-9]+$");
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

    /** Normalizes phone bases independently of extensions, retaining short or unprefixed fragments. */
    public static String phone(String raw) {
        return phoneProjection(raw).digits();
    }

    /** Returns an equality key for a valid complete phone query, or null for a digit fragment. */
    public static String phoneExact(String pattern) {
        PhoneProjection projection = phoneProjection(unescapeContains(pattern));
        return projection.whole() ? projection.digits() : null;
    }

    private static PhoneProjection phoneProjection(String raw) {
        if (raw == null || raw.length() > 256) {
            return new PhoneProjection("", false);
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder compact = new StringBuilder();
        normalized.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
            && !Character.isSpaceChar(codePoint)).forEach(compact::appendCodePoint);
        String base = PHONE_EXTENSION.matcher(compact).replaceFirst("");
        if (base.startsWith("+81(0)")) {
            base = "+81" + base.substring(6);
        }
        StringBuilder digits = new StringBuilder();
        boolean international = false;
        for (int codePoint : base.codePoints().toArray()) {
            if (codePoint >= '0' && codePoint <= '9') {
                digits.appendCodePoint(codePoint);
            } else if (codePoint == '+' && digits.isEmpty() && !international) {
                international = true;
            } else if ("()./\u30fb\u30fc-\u2010\u2011\u2012\u2013\u2014\u2212".indexOf(codePoint) < 0) {
                return new PhoneProjection("", false);
            }
        }
        if (digits.isEmpty()) {
            return new PhoneProjection("", false);
        }
        if (!international && digits.charAt(0) != '0') {
            return new PhoneProjection(digits.toString(), false);
        }
        PhoneNumberUtil phones = PhoneNumberUtil.getInstance();
        String parseable = international ? "+" + digits : digits.toString();
        try {
            var parsed = phones.parse(parseable, "JP");
            if (phones.isValidNumber(parsed) && !parsed.hasExtension()) {
                return new PhoneProjection(
                    phones.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164).substring(1), true);
            }
        } catch (NumberParseException exception) {
            return new PhoneProjection(digits.toString(), false);
        }
        return new PhoneProjection(digits.toString(), false);
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

    private record PhoneProjection(String digits, boolean whole) {
    }
}
