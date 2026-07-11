package ooo.klae.connex.backend.ai.masking;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Boundary for converting raw identifiers into request-local tokens. Structured identifiers are
 * tokenized through {@link #maskField(EntityKind, String, MaskingContext)}, and free text always
 * passes {@link SpecialCareTextScreen} before any identifier substitution. Suspected special-care
 * free text is excluded with a fixed sentinel rather than masked and sent.
 */
public final class MaskingEngine {
    public static final String OMITTED_BY_POLICY = "[omitted by policy]";
    public static final String REDACTED = "[redacted]";

    /** Conservative RFC-lite detector for email addresses in uncontrolled text. */
    private static final Pattern EMAIL_ADDRESS =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** Detector for HTTP(S) URLs and bare {@code www.} host references. */
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);

    /** Detector for phone-like values containing at least seven digits. */
    private static final Pattern PHONE_LIKE_RUN =
            Pattern.compile("(?<![\\p{L}\\p{N}])(?:[+() .-]*[0-9]){7,}(?![\\p{L}\\p{N}])");

    /** Catch-all detector for long account or identifier digit runs. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("(?<![0-9])[0-9]{9,}(?![0-9])");

    /** Whitespace detector used to make multi-word identifier matching flexible. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MaskingEngine() {
    }

    /**
     * Masks an uncontrolled free-text CRM value for provider use.
     * @param text free-text value
     * @param ctx request-local masking context populated from structured fields
     * @return masked text, or a fixed omission sentinel when policy excludes the value
     */
    public static String maskFreeText(String text, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalizedText = Normalizer.normalize(text, Normalizer.Form.NFKC);
        String sanitizedText = stripInjectedTokenDelimiters(normalizeSeparators(normalizedText));
        SpecialCareTextScreen.ScreenVerdict verdict = SpecialCareTextScreen.screen(sanitizedText);
        if (verdict.excluded()) {
            return OMITTED_BY_POLICY;
        }
        String masked = sanitizedText;
        for (MaskingContext.IdentifierEntry entry : ctx.identifierEntriesByLongestRawValue()) {
            masked = identifierPattern(entry.rawValue()).matcher(masked)
                    .replaceAll(Matcher.quoteReplacement(entry.token()));
        }
        masked = EMAIL_ADDRESS.matcher(masked).replaceAll(Matcher.quoteReplacement(REDACTED));
        masked = URL.matcher(masked).replaceAll(Matcher.quoteReplacement(REDACTED));
        masked = PHONE_LIKE_RUN.matcher(masked).replaceAll(Matcher.quoteReplacement(REDACTED));
        masked = LONG_DIGIT_RUN.matcher(masked).replaceAll(Matcher.quoteReplacement(REDACTED));
        return masked;
    }

    /**
     * Tokenizes a structured identifier field.
     * @param kind identifier namespace
     * @param rawValue original CRM display value
     * @param ctx request-local masking context
     * @return request-local placeholder
     */
    public static String maskField(EntityKind kind, String rawValue, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return ctx.tokenFor(kind, rawValue);
    }

    private static String stripInjectedTokenDelimiters(String value) {
        return value.replace("{{", "").replace("}}", "");
    }

    private static String normalizeSeparators(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                normalized.append(' ');
            } else {
                normalized.appendCodePoint(codePoint);
            }
        });
        return normalized.toString();
    }

    private static Pattern identifierPattern(String rawValue) {
        String normalizedValue = Normalizer.normalize(rawValue, Normalizer.Form.NFKC).trim();
        String quoted = Arrays.stream(WHITESPACE.split(normalizedValue))
                .map(Pattern::quote)
                .collect(Collectors.joining("\\s+"));
        if (usesAsciiWordBoundary(normalizedValue)) {
            return Pattern.compile("(?<![\\p{L}\\p{N}_])" + quoted + "(?![\\p{L}\\p{N}_])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        return Pattern.compile(quoted, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static boolean usesAsciiWordBoundary(String value) {
        if (value.isBlank()) {
            return false;
        }
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        return isAsciiLetterOrDigit(first) && isAsciiLetterOrDigit(last);
    }

    private static boolean isAsciiLetterOrDigit(int codePoint) {
        return codePoint >= '0' && codePoint <= '9'
                || codePoint >= 'A' && codePoint <= 'Z'
                || codePoint >= 'a' && codePoint <= 'z';
    }
}
