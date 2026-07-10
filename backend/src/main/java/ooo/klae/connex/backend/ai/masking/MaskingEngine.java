package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boundary for converting raw identifiers into request-local tokens. Structured identifiers are
 * tokenized through {@link #maskField(EntityKind, String, MaskingContext)}, and free text always
 * passes {@link SpecialCareTextScreen} before any identifier substitution. Suspected special-care
 * free text is excluded with a fixed sentinel rather than masked and sent.
 */
public final class MaskingEngine {
    public static final String OMITTED_BY_POLICY = "[omitted by policy]";

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
        SpecialCareTextScreen.ScreenVerdict verdict = SpecialCareTextScreen.screen(text);
        if (verdict.excluded()) {
            return OMITTED_BY_POLICY;
        }
        String masked = text;
        for (MaskingContext.IdentifierEntry entry : ctx.identifierEntriesByLongestRawValue()) {
            masked = identifierPattern(entry.rawValue()).matcher(masked)
                    .replaceAll(Matcher.quoteReplacement(entry.token()));
        }
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

    private static Pattern identifierPattern(String rawValue) {
        String quoted = Pattern.quote(rawValue);
        if (usesAsciiWordBoundary(rawValue)) {
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
