package ooo.klae.connex.backend.ai.masking;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    private static final Pattern ISO_TEMPORAL = Pattern.compile(
            "(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}"
                    + "(?:[T ][0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?"
                    + "(?:Z|[+\\-][0-9]{2}:[0-9]{2})?)?(?![0-9])");

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*([A-Z][1-9][0-9]*)\\s*}}");

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
        String specialCareScreeningText = WHITESPACE.matcher(sanitizedText).replaceAll(" ");
        SpecialCareTextScreen.ScreenVerdict verdict =
                SpecialCareTextScreen.screen(specialCareScreeningText);
        if (verdict.excluded()) {
            return OMITTED_BY_POLICY;
        }
        if (ctx.privacyMode() == ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED) {
            return redactContactData(sanitizedText);
        }
        return maskSanitizedFreeText(sanitizedText, ctx);
    }

    /**
     * Checks whether a tenant identifier would collide with immutable server-controlled prompt
     * text under the same normalized substring matching used by the outbound leak scan.
     * @param trustedStaticText server-controlled prompt text
     * @param rawIdentifier tenant identifier considered for binding
     * @return true when binding the identifier would make immutable prompt text trip the leak scan
     */
    public static boolean trustedStaticTextContainsIdentifier(
            String trustedStaticText, String rawIdentifier) {
        Objects.requireNonNull(trustedStaticText, "trustedStaticText");
        if (rawIdentifier == null || rawIdentifier.isBlank()) {
            throw new IllegalArgumentException("Cannot inspect a blank identifier");
        }
        String normalizedIdentifier = WHITESPACE.matcher(
                Normalizer.normalize(
                        rawIdentifier.replace("{{", "").replace("}}", "").trim(),
                        Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT)).replaceAll(" ");
        String normalizedTrustedText = WHITESPACE.matcher(
                Normalizer.normalize(
                        normalizeSeparators(trustedStaticText),
                        Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT)).replaceAll(" ");
        return normalizedIdentifier.length() >= 4
                && normalizedTrustedText.contains(normalizedIdentifier);
    }

    /**
     * Masks untrusted model output while retaining only placeholders already issued in the current
     * request. This is used when masked output is returned to a provider for schema repair.
     * @param text untrusted, already-masked model output
     * @param ctx request-local masking context populated before the original provider call
     * @return safely masked text with issued placeholders preserved in canonical form
     */
    public static String maskFreeTextPreservingIssuedPlaceholders(String text, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalizedText = normalizeSeparators(Normalizer.normalize(text, Normalizer.Form.NFKC));
        Matcher screeningMatcher = PLACEHOLDER.matcher(normalizedText);
        StringBuilder screeningText = new StringBuilder(normalizedText.length());
        List<Integer> issuedPlaceholderOffsets = new ArrayList<>();
        int screeningEnd = 0;
        while (screeningMatcher.find()) {
            screeningText.append(stripInjectedTokenDelimiters(
                    normalizedText.substring(screeningEnd, screeningMatcher.start())));
            String token = canonicalToken(screeningMatcher.group(1));
            if (ctx.originalValueForToken(token) == null) {
                screeningText.append(stripInjectedTokenDelimiters(screeningMatcher.group()));
            } else {
                issuedPlaceholderOffsets.add(screeningText.length());
            }
            screeningEnd = screeningMatcher.end();
        }
        screeningText.append(stripInjectedTokenDelimiters(normalizedText.substring(screeningEnd)));
        String screened = screeningText.toString();
        String specialCareScreeningText = WHITESPACE.matcher(screened).replaceAll(" ");
        if (SpecialCareTextScreen.screen(specialCareScreeningText).excluded()) {
            return OMITTED_BY_POLICY;
        }
        if (sensitiveValueCrossesIssuedPlaceholder(screened, issuedPlaceholderOffsets, ctx)) {
            return REDACTED;
        }

        Matcher placeholderMatcher = PLACEHOLDER.matcher(normalizedText);
        StringBuilder masked = new StringBuilder(normalizedText.length());
        int maskedEnd = 0;
        while (placeholderMatcher.find()) {
            String token = canonicalToken(placeholderMatcher.group(1));
            if (ctx.originalValueForToken(token) == null) {
                continue;
            }
            masked.append(maskSanitizedFreeText(
                    stripInjectedTokenDelimiters(normalizedText.substring(maskedEnd, placeholderMatcher.start())),
                    ctx));
            masked.append(token);
            maskedEnd = placeholderMatcher.end();
        }
        masked.append(maskSanitizedFreeText(
                stripInjectedTokenDelimiters(normalizedText.substring(maskedEnd)), ctx));
        return masked.toString();
    }

    private static String maskSanitizedFreeText(String sanitizedText, MaskingContext ctx) {
        String masked = sanitizedText;
        for (MaskingContext.IdentifierEntry entry : ctx.identifierEntriesByLongestRawValue()) {
            masked = identifierPattern(entry.rawValue()).matcher(masked)
                    .replaceAll(Matcher.quoteReplacement(entry.token()));
        }
        return redactContactData(masked);
    }

    private static String redactContactData(String text) {
        String redacted = EMAIL_ADDRESS.matcher(text).replaceAll(Matcher.quoteReplacement(REDACTED));
        redacted = URL.matcher(redacted).replaceAll(Matcher.quoteReplacement(REDACTED));
        redacted = PHONE_LIKE_RUN.matcher(redacted).replaceAll(Matcher.quoteReplacement(REDACTED));
        return LONG_DIGIT_RUN.matcher(redacted).replaceAll(Matcher.quoteReplacement(REDACTED));
    }

    private static String canonicalToken(String tokenBody) {
        return "{{" + tokenBody + "}}";
    }

    private static boolean sensitiveValueCrossesIssuedPlaceholder(
            String text,
            List<Integer> issuedPlaceholderOffsets,
            MaskingContext ctx) {
        if (issuedPlaceholderOffsets.isEmpty()) {
            return false;
        }
        for (MaskingContext.IdentifierEntry entry : ctx.identifierEntriesByLongestRawValue()) {
            if (hasCrossingMatch(identifierPattern(entry.rawValue()), text, issuedPlaceholderOffsets)) {
                return true;
            }
        }
        return hasCrossingMatch(EMAIL_ADDRESS, text, issuedPlaceholderOffsets)
                || hasCrossingMatch(URL, text, issuedPlaceholderOffsets)
                || hasCrossingMatch(PHONE_LIKE_RUN, text, issuedPlaceholderOffsets)
                || hasCrossingMatch(LONG_DIGIT_RUN, text, issuedPlaceholderOffsets);
    }

    private static boolean hasCrossingMatch(
            Pattern pattern,
            String text,
            List<Integer> issuedPlaceholderOffsets) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int offset : issuedPlaceholderOffsets) {
                if (matcher.start() < offset && offset < matcher.end()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Preserves a validated structured ISO date or timestamp while retaining free-text redaction
     * for every value that is not exactly a supported temporal representation.
     * @param value structured temporal field value
     * @param ctx request-local masking context
     * @return validated temporal value or the normally masked fallback
     */
    public static String maskTemporal(String value, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = normalizeSeparators(Normalizer.normalize(value, Normalizer.Form.NFKC)).strip();
        if (ISO_TEMPORAL.matcher(normalized).matches() && isValidIsoTemporal(normalized)) {
            return normalized;
        }
        return maskFreeText(normalized, ctx);
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
        if (ctx.privacyMode() == ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED) {
            Objects.requireNonNull(kind, "kind");
            if (rawValue == null || rawValue.isBlank()) {
                throw new IllegalArgumentException("Cannot disclose a blank identifier");
            }
            return stripInjectedTokenDelimiters(normalizeSeparators(
                    Normalizer.normalize(rawValue, Normalizer.Form.NFKC))).strip();
        }
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

    private static boolean isValidIsoTemporal(String value) {
        try {
            if (value.length() == 10) {
                LocalDate.parse(value);
            } else {
                String normalized = value.replace(' ', 'T');
                if (normalized.endsWith("Z") || hasOffset(normalized)) {
                    OffsetDateTime.parse(normalized);
                } else {
                    LocalDateTime.parse(normalized);
                }
            }
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static boolean hasOffset(String value) {
        int timeSeparator = value.indexOf('T');
        int plus = value.lastIndexOf('+');
        int minus = value.lastIndexOf('-');
        return plus > timeSeparator || minus > timeSeparator;
    }

}
