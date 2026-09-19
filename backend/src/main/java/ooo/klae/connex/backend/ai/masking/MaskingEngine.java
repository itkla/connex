package ooo.klae.connex.backend.ai.masking;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ooo.klae.connex.backend.ai.AiCancellation;

/**
 * Boundary for converting raw identifiers into request-local tokens. Structured identifiers are
 * tokenized through {@link #maskField(EntityKind, String, MaskingContext)}, and free text always
 * passes {@link SpecialCareTextScreen} before any identifier substitution. Suspected special-care
 * free text is excluded with a fixed sentinel rather than masked and sent.
 */
public final class MaskingEngine {
    public static final String OMITTED_BY_POLICY = "[omitted by policy]";
    public static final String REDACTED = "[redacted]";

    /**
     * Detector for HTTP(S) URLs and bare {@code www.} host references. Its literal prefix rejects
     * a start position in constant time and its single greedy run has nothing following it to
     * force a retry, so screening complete pre-truncation text stays linear.
     */
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);

    /** Minimum digit count that makes a separated digit run phone-like. */
    private static final int PHONE_LIKE_MIN_DIGITS = 7;

    /**
     * Catch-all detector for long account or identifier digit runs. Its single greedy digit run
     * consumes a maximal run whose following character can never be a digit, so the trailing
     * assertion succeeds without backtracking and screening stays linear.
     */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("(?<![0-9])[0-9]{9,}(?![0-9])");

    private static final Pattern ISO_TEMPORAL = Pattern.compile(
            "(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}"
                    + "(?:[T ][0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?"
                    + "(?:Z|[+\\-][0-9]{2}:[0-9]{2})?)?(?![0-9])");

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*([A-Z][1-9][0-9]*)\\s*}}");

    private static final Pattern REPLAY_HANDLE = Pattern.compile("(?<![A-Za-z0-9_])r[1-9][0-9]*(?![A-Za-z0-9_])");

    private MaskingEngine() {
    }

    /**
     * Masks an uncontrolled free-text CRM value for provider use.
     * @param text free-text value
     * @param ctx request-local masking context populated from structured fields
     * @return masked text, or a fixed omission sentinel when policy excludes the value
     */
    public static String maskFreeText(String text, MaskingContext ctx) {
        return maskFreeText(text, ctx, Map.of());
    }

    /** Screens compaction prose while resolving authorized historical references after masking. */
    public static String maskFreeText(String text, MaskingContext ctx, Map<String, String> replayHandles) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(replayHandles, "replayHandles");
        if (text == null || text.isBlank()) {
            return "";
        }
        ScreenText input = screenText(text);
        String specialCareScreeningText = input.labels().value();
        SpecialCareTextScreen.ScreenVerdict verdict =
                SpecialCareTextScreen.screen(specialCareScreeningText);
        if (verdict.excluded()) {
            return OMITTED_BY_POLICY;
        }
        if (ctx.privacyMode() == ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED) {
            return replaceReplaySpans(input,
                    mergeSensitiveSpans(new ArrayList<>(contactDataSpans(input))),
                    replayHandles.isEmpty() ? List.of() : sensitiveSpans(input, ctx, false, false), replayHandles);
        }
        return maskReplay(input, ctx, false, replayHandles);
    }

    /**
     * Masks conversational text — the member's own words and prior answers replayed as history —
     * while leaving common words intact when a record shares their name.
     *
     * <p>A company named "what" must not turn the question "what is each one waiting on?" into
     * token soup: when the registered server text provably contains the identifier's value, the
     * word carries no tenant signal (the server emits it in every prompt and the leak scan already
     * exempts it), so conversational occurrences keep their ordinary meaning. Structured CRM
     * fields never take this path — a record's own name field always tokenizes.
     *
     * @param text conversational free-text value
     * @param ctx request-local masking context with registered trusted server text
     * @return masked text with collision-exempt common words preserved
     */
    public static String maskConversationalFreeText(String text, MaskingContext ctx) {
        return maskConversationalFreeText(text, ctx, Map.of());
    }

    /**
     * Masks original historical prose, resolves authorized references once, then screens names
     * reconstructed by remapping while preserving issued tokens. Original identifier and contact
     * spans take precedence over handles, so a seeded name such as {@code r1 Logistics} retains
     * its identity. Reference spans map canonical display preparation back to the original text.
     *
     * @param text original historical prose
     * @param ctx current request's masking dictionary
     * @param replayHandles historical handles mapped to currently authorized handles
     * @return screened prose with sensitive spans masked and surviving references remapped
     */
    public static String maskConversationalFreeText(
            String text, MaskingContext ctx, Map<String, String> replayHandles) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(replayHandles, "replayHandles");
        if (text == null || text.isBlank()) {
            return "";
        }
        ScreenText input = screenText(text);
        String specialCareScreeningText = input.labels().value();
        SpecialCareTextScreen.ScreenVerdict verdict =
                SpecialCareTextScreen.screen(specialCareScreeningText);
        if (verdict.excluded()) {
            return OMITTED_BY_POLICY;
        }
        if (ctx.privacyMode() == ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED) {
            return replaceReplaySpans(input,
                    mergeSensitiveSpans(new ArrayList<>(contactDataSpans(input))),
                    replayHandles.isEmpty() ? List.of() : sensitiveSpans(input, ctx, false, false), replayHandles);
        }
        return maskReplay(input, ctx, true, replayHandles);
    }

    /** Screens original identifiers before remapping, then newly formed identifiers with tokens protected. */
    private static String maskReplay(ScreenText input, MaskingContext ctx,
            boolean preserveTrustedCollisions, Map<String, String> replayHandles) {
        String masked = replaceReplaySpans(input,
                sensitiveSpans(input, ctx, preserveTrustedCollisions, true), replayHandles);
        if (!replayHandles.isEmpty()) {
            masked = maskFreeTextPreservingIssuedPlaceholders(masked, ctx, preserveTrustedCollisions);
        }
        return masked;
    }

    private static String replaceReplaySpans(
            ScreenText input, List<ReplacementSpan> sensitive, Map<String, String> replayHandles) {
        return replaceReplaySpans(input, sensitive, sensitive, replayHandles);
    }

    private static String replaceReplaySpans(
            ScreenText input, List<ReplacementSpan> replacements, List<ReplacementSpan> sensitive,
            Map<String, String> replayHandles) {
        if (replayHandles.isEmpty()) {
            return replaceSpans(input.original(), replacements, input.linkSyntax());
        }
        List<ReplacementSpan> spans = new ArrayList<>(replacements);
        CanonicalText.Projection prepared = input.prepared();
        Matcher handles = REPLAY_HANDLE.matcher(prepared.value());
        int sensitiveIndex = 0;
        while (handles.find()) {
            int start = prepared.sourceStart(handles.start());
            int end = prepared.sourceEnd(handles.end());
            while (sensitiveIndex < sensitive.size() && sensitive.get(sensitiveIndex).end() <= start) {
                sensitiveIndex++;
            }
            if (sensitiveIndex < sensitive.size() && sensitive.get(sensitiveIndex).start() < end) {
                continue;
            }
            String replacement = replayHandles.get(handles.group());
            if (replacement != null) {
                spans.add(new ReplacementSpan(start, end, replacement, false, 0, ""));
            }
        }
        spans.sort(Comparator.comparingInt(ReplacementSpan::start));
        return replaceSpans(input.original(), spans, input.linkSyntax());
    }

    /**
     * Screens complete uncontrolled text against a populated masking dictionary before a caller
     * applies any character boundary. Known identifiers are irreversibly redacted here because a
     * later truncation could otherwise split a tokenizable value into an unrecognizable fragment.
     * @param text complete free-text value
     * @param ctx request-local masking context populated from structured fields
     * @return normalized text with policy exclusions and sensitive values removed
     */
    public static String screenFreeTextBeforeTruncation(String text, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (text == null || text.isBlank()) {
            return "";
        }
        ScreenText input = screenText(text);
        if (SpecialCareTextScreen.screen(input.labels().value()).excluded()) {
            return OMITTED_BY_POLICY;
        }
        List<ReplacementSpan> spans = ctx.privacyMode() == ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED
                ? mergeSensitiveSpans(new ArrayList<>(contactDataSpans(input)))
                : sensitiveSpans(input, ctx, false, false);
        return replaceSpans(text, spans, input.linkSyntax());
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
        String normalizedIdentifier = OutboundLeakScan.normalizeForScan(rawIdentifier);
        String normalizedTrustedText = OutboundLeakScan.normalizeForScan(trustedStaticText);
        String labelIdentifier = CanonicalText.storedIdentifier(rawIdentifier).label();
        return normalizedIdentifier.length() >= OutboundLeakScan.MIN_IDENTIFIER_LENGTH
                && normalizedTrustedText.contains(normalizedIdentifier)
                || labelIdentifier.length() >= OutboundLeakScan.MIN_IDENTIFIER_LENGTH
                && normalizedTrustedText.contains(labelIdentifier);
    }

    /**
     * Masks untrusted model output while retaining only placeholders already issued in the current
     * request. This is used when masked output is returned to a provider for schema repair.
     * @param text untrusted, already-masked model output
     * @param ctx request-local masking context populated before the original provider call
     * @return safely masked text with issued placeholders preserved in canonical form
     */
    public static String maskFreeTextPreservingIssuedPlaceholders(String text, MaskingContext ctx) {
        return maskFreeTextPreservingIssuedPlaceholders(text, ctx, false);
    }

    private static String maskFreeTextPreservingIssuedPlaceholders(
            String text, MaskingContext ctx, boolean preserveTrustedCollisions) {
        Objects.requireNonNull(ctx, "ctx");
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalizedText = text;
        Matcher screeningMatcher = PLACEHOLDER.matcher(normalizedText);
        StringBuilder screeningText = new StringBuilder(normalizedText.length());
        List<Integer> issuedPlaceholderOffsets = new ArrayList<>();
        int screeningEnd = 0;
        while (screeningMatcher.find()) {
            screeningText.append(canonicalizeText(
                    normalizedText.substring(screeningEnd, screeningMatcher.start())));
            String token = canonicalToken(screeningMatcher.group(1));
            if (ctx.originalValueForToken(token) == null) {
                screeningText.append(canonicalizeText(screeningMatcher.group()));
            } else {
                issuedPlaceholderOffsets.add(screeningText.length());
            }
            screeningEnd = screeningMatcher.end();
        }
        screeningText.append(canonicalizeText(normalizedText.substring(screeningEnd)));
        String screened = screeningText.toString();
        String specialCareScreeningText = CanonicalText.projectLabels(screened).value();
        if (SpecialCareTextScreen.screen(specialCareScreeningText).excluded()) {
            return OMITTED_BY_POLICY;
        }
        if (sensitiveValueCrossesIssuedPlaceholder(screened, issuedPlaceholderOffsets, ctx)) {
            return REDACTED;
        }

        boolean[] linkSyntax = linkSyntax(normalizedText, CanonicalText.project(normalizedText));
        Matcher placeholderMatcher = PLACEHOLDER.matcher(normalizedText);
        StringBuilder masked = new StringBuilder(normalizedText.length());
        int maskedEnd = 0;
        while (placeholderMatcher.find()) {
            String token = canonicalToken(placeholderMatcher.group(1));
            if (ctx.originalValueForToken(token) == null) {
                continue;
            }
            String segment = normalizedText.substring(maskedEnd, placeholderMatcher.start());
            masked.append(protectIssuedTokens(replaceSpans(segment,
                    sensitiveSpans(segment, ctx, preserveTrustedCollisions, true),
                    Arrays.copyOfRange(linkSyntax, maskedEnd, placeholderMatcher.start()))));
            if (hasVisibleSource(linkSyntax, placeholderMatcher.start(), placeholderMatcher.end())) {
                masked.append(protectIssuedTokens(token));
            }
            maskedEnd = placeholderMatcher.end();
        }
        String segment = normalizedText.substring(maskedEnd);
        masked.append(protectIssuedTokens(replaceSpans(segment,
                sensitiveSpans(segment, ctx, preserveTrustedCollisions, true),
                Arrays.copyOfRange(linkSyntax, maskedEnd, normalizedText.length()))));
        return ConversationText.finishMasked(masked.toString());
    }

    /**
     * Keeps source offsets while exposing the boundaries earlier contact redactions create.
     * The neutral character is neither a word character nor whitespace: phone boundaries see a
     * redaction boundary, while a URL surrounding a redacted email still consumes its full tail.
     */
    private static List<ReplacementSpan> contactDataSpans(ScreenText input) {
        List<ReplacementSpan> spans = new ArrayList<>(contactDataSpans(input.prepared()));
        if (!input.labels().value().equals(input.prepared().value())) {
            spans.addAll(contactDataSpans(input.labels()));
        }
        spans.removeIf(span -> !hasVisibleSource(input.linkSyntax(), span.start(), span.end()));
        return spans;
    }

    private static List<ReplacementSpan> contactDataSpans(CanonicalText.Projection prepared) {
        String text = prepared.value();
        List<ReplacementSpan> spans = new ArrayList<>();
        char[] workingText = text.toCharArray();
        collectContactSpans(spans, emailSpans(text), workingText);
        collectContactSpans(spans, patternSpans(URL, new String(workingText)), workingText);
        collectContactSpans(spans, phoneLikeSpans(new String(workingText)), workingText);
        collectContactSpans(spans, patternSpans(LONG_DIGIT_RUN, new String(workingText)), workingText);
        return spans.stream().map(span -> new ReplacementSpan(prepared.sourceStart(span.start()),
                prepared.sourceEnd(span.end()), span.replacement(), true, 0, "")).toList();
    }

    private static void collectContactSpans(
            List<ReplacementSpan> target, List<TextSpan> spans, char[] workingText) {
        addReplacementSpans(target, spans, REDACTED, true);
        for (TextSpan span : spans) {
            Arrays.fill(workingText, span.start(), span.end(), ']');
        }
    }

    private static List<TextSpan> patternSpans(Pattern pattern, String text) {
        List<TextSpan> spans = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            spans.add(new TextSpan(matcher.start(), matcher.end()));
        }
        return spans;
    }

    /**
     * Finds the same greedy, non-overlapping RFC-lite emails as the former regex in linear time.
     * Each at-sign delimits disjoint local/domain runs; a run is visited at most once per side.
     * The last valid alphabetic suffix preserves regex backtracking over an invalid domain tail.
     */
    private static List<TextSpan> emailSpans(String text) {
        List<TextSpan> matches = new ArrayList<>();
        int previousEnd = 0;
        for (int at = text.indexOf('@'); at >= 0; at = text.indexOf('@', at + 1)) {
            int start = at;
            while (start > previousEnd && isEmailLocalCharacter(text.charAt(start - 1))) {
                start--;
            }
            if (start == at) {
                continue;
            }
            int matchEnd = -1;
            int suffixLetters = -1;
            for (int end = at + 1; end < text.length() && isEmailDomainCharacter(text.charAt(end)); end++) {
                char character = text.charAt(end);
                if (character == '.' && end > at + 1) {
                    suffixLetters = 0;
                } else if (isAsciiLetter(character) && suffixLetters >= 0) {
                    suffixLetters++;
                    if (suffixLetters >= 2) {
                        matchEnd = end + 1;
                    }
                } else {
                    suffixLetters = -1;
                }
            }
            if (matchEnd >= 0) {
                matches.add(new TextSpan(start, matchEnd));
                previousEnd = matchEnd;
            }
        }
        return matches;
    }

    /**
     * Finds the same greedy, non-overlapping phone-like digit runs as the former unanchored
     * pattern, in time linear in the text length.
     *
     * <p>The former pattern paired a greedy separator run with a greedy repetition, so a long
     * separator-only or digit-only stretch of uncontrolled note text cost quadratic backtracking
     * at every start position and could exhaust the JVM stack before any truncation ran. Masking
     * screens complete text before truncation, so that cost was reachable from ordinary CRM notes.
     *
     * <p>Each maximal run of digits and telephone separators is visited once. Within a run the
     * earliest position the former lookbehind admitted is the span start, and the span ends at the
     * last digit that carries at least {@link #PHONE_LIKE_MIN_DIGITS} digits and is not followed by
     * a letter or digit — exactly the repetition count the former greedy match settled on. A run
     * yields at most one span, because every digit after that point failed the same trailing test.
     */
    private static List<TextSpan> phoneLikeSpans(String text) {
        List<TextSpan> spans = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            if (!isPhoneRunCharacter(text.charAt(index))) {
                index++;
                continue;
            }
            int runEnd = index;
            while (runEnd < text.length() && isPhoneRunCharacter(text.charAt(runEnd))) {
                runEnd++;
            }
            TextSpan span = phoneLikeSpanInRun(text, index, runEnd);
            if (span != null) {
                spans.add(span);
            }
            index = runEnd;
        }
        return spans;
    }

    /**
     * Resolves the single phone-like span inside one maximal digit-and-separator run.
     * @param text complete text being screened
     * @param runStart first index of the run
     * @param runEnd index after the run
     * @return the span the former pattern would have matched, or {@code null} when it matched none
     */
    private static TextSpan phoneLikeSpanInRun(String text, int runStart, int runEnd) {
        int start = runStart;
        if (start > 0 && isPhoneBoundaryCharacter(leadingBoundaryCodePoint(text, start))) {
            start = -1;
            for (int probe = runStart + 1; probe < runEnd; probe++) {
                if (!isAsciiDigit(text.charAt(probe - 1))) {
                    start = probe;
                    break;
                }
            }
            if (start < 0) {
                return null;
            }
        }
        int digits = 0;
        int end = -1;
        for (int probe = start; probe < runEnd; probe++) {
            if (!isAsciiDigit(text.charAt(probe))) {
                continue;
            }
            if (++digits < PHONE_LIKE_MIN_DIGITS) {
                continue;
            }
            boolean trailingBoundary = probe + 1 < runEnd
                    ? !isAsciiDigit(text.charAt(probe + 1))
                    : runEnd == text.length() || !isPhoneBoundaryCharacter(text.codePointAt(runEnd));
            if (trailingBoundary) {
                end = probe + 1;
            }
        }
        return end < 0 ? null : new TextSpan(start, end);
    }

    private static boolean[] linkSyntax(String text, CanonicalText.Projection literal) {
        boolean[] linkSyntax = new boolean[text.length()];
        for (ConversationText.SourceSpan span : ConversationText.linkSyntax(literal)) {
            Arrays.fill(linkSyntax, span.start(), span.end(), true);
        }
        return linkSyntax;
    }

    private static String replaceSpans(String text, List<ReplacementSpan> spans, boolean[] linkSyntax) {
        StringBuilder replaced = new StringBuilder(text.length());
        int copiedEnd = 0;
        for (ReplacementSpan span : spans) {
            appendUnmaskedSource(replaced, text, linkSyntax, copiedEnd, span.start());
            if (hasVisibleSource(linkSyntax, span.start(), span.end())) {
                replaced.append(protectIssuedTokens(span.replacement()));
            }
            copiedEnd = span.end();
        }
        appendUnmaskedSource(replaced, text, linkSyntax, copiedEnd, text.length());
        return ConversationText.finishMasked(replaced.toString());
    }

    private static boolean hasVisibleSource(boolean[] linkSyntax, int start, int end) {
        for (int offset = start; offset < end; offset++) {
            if (!linkSyntax[offset]) {
                return true;
            }
        }
        return false;
    }

    private static void appendUnmaskedSource(
            StringBuilder target, String text, boolean[] linkSyntax, int start, int end) {
        StringBuilder retained = new StringBuilder(end - start);
        for (int offset = start; offset < end; offset++) {
            if (!linkSyntax[offset]) {
                retained.append(text.charAt(offset));
            }
        }
        target.append(CanonicalText.prepare(retained.toString()));
    }

    /** Called only for emitted replacements or segments whose raw placeholder syntax was cancelled. */
    private static String protectIssuedTokens(String masked) {
        return PLACEHOLDER.matcher(masked).replaceAll("\u0000$1\u0000");
    }

    private static boolean isEmailLocalCharacter(char character) {
        return isEmailDomainCharacter(character) || character == '_' || character == '%'
                || character == '+';
    }

    private static boolean isEmailDomainCharacter(char character) {
        return isAsciiLetterOrDigit(character) || character == '.' || character == '-';
    }

    private static boolean isAsciiLetter(int character) {
        return character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
    }

    private static boolean isAsciiDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isPhoneRunCharacter(char character) {
        return isAsciiDigit(character) || character == '+' || character == '(' || character == ')'
                || character == ' ' || character == '.' || character == '-';
    }

    /**
     * Reads the character preceding a run the way the former pattern's one-code-unit lookbehind
     * read it. That lookbehind stepped back exactly one {@code char}, so a low surrogate was
     * examined on its own and a digit run starting immediately after a supplementary-plane letter
     * still counted as phone-like. Combining the pair here instead would silently narrow the
     * redactor relative to the pattern this scanner replaced.
     *
     * @param text text being screened
     * @param index first index of the run
     * @return the boundary code point, or the unpaired surrogate when that is what precedes it
     */
    private static int leadingBoundaryCodePoint(String text, int index) {
        return Character.codePointAt(text, index - 1);
    }

    /** Mirrors the {@code \p{L}} and {@code \p{N}} boundary the former phone pattern required. */
    private static boolean isPhoneBoundaryCharacter(int codePoint) {
        if (Character.isLetter(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    private record TextSpan(int start, int end) {
    }

    private record ReplacementSpan(
            int start, int end, String replacement, boolean contactData, int priority, String identity) {
    }

    /** Splits at complete issued source tokens before any destructive canonicalization. */
    static List<String> unprotectedTextSegments(String text, MaskingContext ctx) {
        List<String> segments = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        int end = 0;
        while (matcher.find()) {
            if (ctx.originalValueForToken(canonicalToken(matcher.group(1))) != null) {
                segments.add(text.substring(end, matcher.start()));
                end = matcher.end();
            }
        }
        segments.add(text.substring(end));
        return segments;
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
        return hasCrossingSpan(sensitiveSpans(text, ctx, false, false), issuedPlaceholderOffsets);
    }

    private static boolean hasCrossingSpan(
            List<ReplacementSpan> spans,
            List<Integer> issuedPlaceholderOffsets) {
        for (ReplacementSpan span : spans) {
            for (int offset : issuedPlaceholderOffsets) {
                if (span.start() < offset && offset < span.end()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Preserves a validated structured ISO date or timestamp while retaining free-text redaction
     * for every value that is not exactly a supported temporal representation. A structured value
     * equal to a seeded unsafe stored name is locally omitted, without relaxing prose screening.
     * @param value structured temporal field value
     * @param ctx request-local masking context
     * @return validated temporal value or the normally masked fallback
     */
    public static String maskTemporal(String value, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (value == null || value.isBlank()) {
            return "";
        }
        if (ctx.isUnsafeIdentifierValue(value)) {
            return REDACTED;
        }
        String normalized = normalizeSeparators(Normalizer.normalize(value, Normalizer.Form.NFKC)).strip();
        if (ISO_TEMPORAL.matcher(normalized).matches() && isValidIsoTemporal(normalized)
                && !ctx.isSeededIdentifierValue(normalized)) {
            return normalized;
        }
        return maskFreeText(normalized, ctx);
    }

    /**
     * Tokenizes a structured identifier field.
     * @param kind identifier namespace
     * @param rawValue original CRM display value
     * @param ctx request-local masking context
     * @return request-local placeholder, or a redaction marker for an unsafe stored identifier
     */
    public static String maskField(EntityKind kind, String rawValue, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (ctx.privacyMode() == ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED) {
            Objects.requireNonNull(kind, "kind");
            if (rawValue == null || rawValue.isBlank()) {
                throw new IllegalArgumentException("Cannot disclose a blank identifier");
            }
            if (!CanonicalText.storedIdentifier(rawValue).converged()) {
                return ctx.tokenFor(kind, rawValue);
            }
            return canonicalizeText(rawValue).strip();
        }
        return ctx.tokenFor(kind, rawValue);
    }

    private static String canonicalizeText(String value) {
        return CanonicalText.prepare(value);
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

    /** Returns the single canonical identifier form used at every masking boundary. */
    static String normalizeIdentifierValue(String rawValue) {
        return CanonicalText.canonical(rawValue);
    }

    /**
     * Decides whether an authorized lookup candidate is mentioned in a user turn, so seeding the
     * masking dictionary covers every value this engine could later have to replace.
     *
     * <p>The gate guarantees admission, not replacement. It normalizes both sides exactly as the
     * replacer does — the original text through the source-mapped, delimiter-blind, whitespace-collapsing,
     * context-free case fold of {@link CanonicalText#project} —
     * and keeps the candidate literal, so punctuation-bearing names are matched as data rather
     * than compiled as a pattern. A value the replacer treats as unbounded (one that does not
     * begin and end with an ASCII letter or digit) is admitted on plain containment, matching
     * the primary matcher. A value the replacer treats as bounded is admitted when an
     * occurrence has no ASCII word character on either side; that ASCII rule is deliberately
     * weaker than the Latin-script boundary the primary matcher applies, because
     * {@link #identifierResidualSpans} still redacts an occurrence that sits against a
     * non-ASCII Latin letter, and {@link OutboundLeakScan} would refuse the whole call over it.
     *
     * <p>Admission is therefore a superset of what the replacer and the outbound scan can act on,
     * within the set of values the dictionary itself accepts: a candidate refused by
     * {@link MaskingContext#isDictionaryEligible} cannot drive replacement, so it is declined here
     * too. Raw scan-only values seeded from structured fields still fail closed at egress.
     * Occurrences it declines — a candidate buried inside a longer ASCII word —
     * are not entity mentions, are never seeded, and so can never make the outbound scan refuse
     * the call.
     *
     * <p>Both operands additionally use the same source-mapped label projection. Literal matches
     * remain available when a stored name straddles complete or incomplete link syntax; link
     * targets never establish authority. Original source spans determine substitution coverage.
     * An exhausted stored-name projection supplies no alias and retains literal matching only;
     * unlike caller-controlled turn text, a stored candidate cannot refuse an unrelated lookup.
     *
     * @param text complete user turn
     * @param rawValue authorized candidate identifier
     * @return whether the text contains a mention this engine would have to mask
     */
    public static boolean containsIdentifierMention(String text, String rawValue) {
        return containsIdentifierMention(mentionScanText(text), rawValue);
    }

    /**
     * Decides the same question against a turn that was normalized once for a whole candidate
     * page, so a bounded lookup does not rescan the turn per candidate.
     *
     * @param scanText user turn prepared by {@link #mentionScanText}
     * @param rawValue authorized candidate identifier
     * @return whether the text contains a mention this engine would have to mask
     */
    public static boolean containsIdentifierMention(MentionScanText scanText, String rawValue) {
        Objects.requireNonNull(scanText, "scanText");
        Objects.requireNonNull(rawValue, "rawValue");
        if (!MaskingContext.isDictionaryEligible(rawValue)) {
            return false;
        }
        String value = normalizeIdentifierValue(rawValue);
        if (containsIdentifierMention(scanText.prepared, scanText.projection, value)) {
            return true;
        }
        String labelValue = CanonicalText.storedIdentifier(rawValue).label();
        return MaskingContext.isCanonicalDictionaryEligible(labelValue)
                && containsIdentifierMention(scanText.prepared, scanText.labels, labelValue);
    }

    private static boolean containsIdentifierMention(
            String prepared, CanonicalText.Projection projection, String value) {
        boolean bounded = usesAsciiWordBoundary(value);
        String canonical = projection.value();
        for (int start = canonical.indexOf(value); start >= 0;
                start = canonical.indexOf(value, start + 1)) {
            int sourceStart = projection.sourceStart(start);
            int sourceEnd = projection.sourceEnd(start + value.length());
            if (!bounded
                    || (sourceStart == 0 || !isIdentifierWord(prepared.codePointBefore(sourceStart)))
                    && (sourceEnd == prepared.length()
                            || !isIdentifierWord(prepared.codePointAt(sourceEnd)))) {
                return true;
            }
        }
        return false;
    }

    /** Prepares one canonical turn and its source-offset map for the whole candidate page. */
    public static MentionScanText mentionScanText(String text) {
        return new MentionScanText(Objects.requireNonNull(text, "text"));
    }

    /** Canonical turn with complete source-span offsets into the original text. */
    public static final class MentionScanText {
        private final String prepared;
        private final CanonicalText.Projection projection;
        private final CanonicalText.Projection labels;

        private MentionScanText(String text) {
            prepared = text;
            projection = CanonicalText.project(prepared);
            labels = CanonicalText.projectLabels(projection);
        }

        /** Returns both source-derived forms as a SQL superset; Java gates each projection separately. */
        public String lookupText() {
            return projection.value().equals(labels.value())
                    ? projection.value() : projection.value() + "\n" + labels.value();
        }

        /** Returns the canonical label form for callers inspecting the case-folded turn. */
        public String normalizedText() {
            return labels.value();
        }

        /** Returns the same canonical form for callers inspecting the case-folded turn. */
        public String foldedText() {
            return labels.value();
        }
    }

    /**
     * The ASCII word set the seeding gate bounds a mention with. It is deliberately narrower than
     * {@link #isResidualWordCharacter}: a candidate touching a character that only case-folds onto
     * an ASCII letter is still admitted, seeded and then redacted by the residual pass, whereas
     * declining it would leave a value the outbound scan can still find unseeded.
     */
    private static boolean isIdentifierWord(int codePoint) {
        return codePoint == '_' || isAsciiLetterOrDigit(codePoint);
    }

    /**
     * Finds every occurrence the outbound leak scan can flag, with no boundary at all, in time
     * linear in the text length.
     *
     * <p>The boundary-respecting primary matcher is the preferred replacement, but the scan checks
     * raw normalized containment: any occurrence the replacer declines that the scan would still
     * find fails the whole provider call closed. This residual pass restores the invariant that
     * the replacer covers at least the scanner, at the scanner's own minimum identifier length.
     * Each span consumes the whole surrounding word run so its redaction reads as a removed word.
     * Replacement separately preserves complete issued placeholders; arbitrary braces grant no
     * protection to raw identifiers.
     *
     * <p>The former expression of this rule paired a greedy leading word run with the quoted value
     * and a greedy trailing word run. The leading run consumed a whole word and gave characters
     * back at every start index, so screening one long uncontrolled note against one identifier
     * cost quadratic backtracking before any truncation ran — the same pre-truncation path the
     * email and phone scanners above had to leave. Every literal occurrence is considered,
     * including occurrences overlapping earlier matches. Reusing the previous merged word-run
     * boundaries avoids scanning those same surrounding characters again for each occurrence.
     *
     * @param text text being screened or masked
     * @param scanText canonical projection of the original text
     * @param value identifier canonicalized by {@link #normalizeIdentifierValue}
     * @return ordered, non-overlapping spans to redact
     */
    private static List<TextSpan> identifierResidualSpans(
            String text, CanonicalText.Projection scanText, String value) {
        String foldedText = scanText.value();
        List<TextSpan> spans = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom <= foldedText.length()) {
            int occurrence = foldedText.indexOf(value, searchFrom);
            if (occurrence < 0) {
                return spans;
            }
            int occurrenceEnd = occurrence + value.length();
            int sourceOccurrence = scanText.sourceStart(occurrence);
            int sourceOccurrenceEnd = scanText.sourceEnd(occurrenceEnd);
            TextSpan previous = spans.isEmpty() ? null : spans.getLast();
            int start = sourceOccurrence;
            int previousEnd = previous == null ? 0 : previous.end();
            while (start > previousEnd && isResidualWordCharacter(text.charAt(start - 1))) {
                start--;
            }
            boolean overlaps = previous != null && start <= previous.end();
            int wordEnd = overlaps ? Math.max(previousEnd, sourceOccurrenceEnd) : sourceOccurrenceEnd;
            while (wordEnd < text.length() && isResidualWordCharacter(text.charAt(wordEnd))) {
                wordEnd++;
            }
            if (overlaps) {
                spans.set(spans.size() - 1, new TextSpan(previous.start(), wordEnd));
            } else {
                spans.add(new TextSpan(start, wordEnd));
            }
            searchFrom = occurrence + 1;
        }
        return spans;
    }

    /**
     * Whether a character belongs to the word run the residual pass absorbs around an occurrence.
     *
     * <p>This is the ASCII word set as a case-insensitive Unicode matcher sees it, so characters
     * that case-fold onto an ASCII letter — the Kelvin sign, the dotted capital I, the dotless i,
     * the long s — count, exactly as they did for the pattern this replaced. Absorbing them keeps
     * the inherited surrounding-word coverage while the identifier itself is matched only by
     * the shared canonical form.
     */
    private static boolean isResidualWordCharacter(int codePoint) {
        return isIdentifierWord(codePoint)
                || isIdentifierWord(Character.toUpperCase(codePoint))
                || isIdentifierWord(Character.toLowerCase(codePoint));
    }

    /** Builds primitive source maps once per input, before iterating over dictionary candidates. */
    static ScreenText screenText(String text) {
        CanonicalText.Projection prepared = CanonicalText.prepareProjection(text);
        CanonicalText.Projection literal = CanonicalText.project(prepared);
        CanonicalText.Projection labels = CanonicalText.projectLabels(literal);
        return new ScreenText(text, prepared, literal, labels, linkSyntax(text, literal));
    }

    /** Shared read-only projections for screening, matching, replay and final source replacement. */
    record ScreenText(String original, CanonicalText.Projection prepared, CanonicalText.Projection literal,
            CanonicalText.Projection labels, boolean[] linkSyntax) {
    }

    /**
     * Collects dictionary matches against unchanged source text and sequential contact matches
     * mapped to that same source. Primary
     * matches may retain their entity token; residual matches consume surrounding word runs and
     * always redact. Resolving their union before substitution prevents any match from destroying
     * the evidence needed to remove another identifier before truncation or provider egress.
     */
    private static List<ReplacementSpan> sensitiveSpans(
            String text, MaskingContext ctx, boolean preserveTrustedCollisions, boolean tokenize) {
        return sensitiveSpans(screenText(text), ctx, preserveTrustedCollisions, tokenize);
    }

    private static List<ReplacementSpan> sensitiveSpans(
            ScreenText input, MaskingContext ctx, boolean preserveTrustedCollisions, boolean tokenize) {
        String text = input.original();
        List<ReplacementSpan> spans = new ArrayList<>(contactDataSpans(input));
        CanonicalText.Projection projection = input.literal();
        CanonicalText.Projection labels = input.labels();
        for (MaskingContext.IdentifierEntry entry : ctx.identifierEntries()) {
            AiCancellation.throwIfInterrupted();
            if (!entry.replacementEligible()
                    || preserveTrustedCollisions && ctx.isTrustedTextCollision(entry.rawValue())) {
                continue;
            }
            addReplacementSpans(spans,
                    coalesceIdentifierRuns(identifierSpans(text, projection, entry.canonicalValue())),
                    tokenize && !entry.unsafe() ? entry.token() : REDACTED, false, 2, entry.rawValue());
            if (residualEligible(entry.canonicalValue())) {
                addReplacementSpans(spans, identifierResidualSpans(text, projection, entry.canonicalValue()), REDACTED, false);
            }
            if (entry.labelEligible()) {
                addReplacementSpans(spans,
                        coalesceIdentifierRuns(identifierSpans(text, labels, entry.labelValue())),
                        tokenize ? entry.token() : REDACTED, false, 1, entry.rawValue());
                if (residualEligible(entry.labelValue())) {
                    addReplacementSpans(spans, identifierResidualSpans(text, labels, entry.labelValue()), REDACTED, false);
                }
            }
        }
        return mergeSensitiveSpans(spans);
    }

    /**
     * Joins overlapping and touching occurrences of one identifier into a single run, so a region
     * built entirely from repetitions of the same value keeps that value's token instead of being
     * outranked by the residual span covering the same characters.
     */
    private static List<TextSpan> coalesceIdentifierRuns(List<TextSpan> spans) {
        List<TextSpan> runs = new ArrayList<>();
        for (TextSpan span : spans) {
            TextSpan previous = runs.isEmpty() ? null : runs.getLast();
            if (previous != null && previous.end() >= span.start()) {
                runs.set(runs.size() - 1,
                        new TextSpan(previous.start(), Math.max(previous.end(), span.end())));
            } else {
                runs.add(span);
            }
        }
        return runs;
    }

    private static void addReplacementSpans(
            List<ReplacementSpan> target, List<TextSpan> spans, String replacement, boolean contactData) {
        addReplacementSpans(target, spans, replacement, contactData, 0, "");
    }

    private static void addReplacementSpans(List<ReplacementSpan> target, List<TextSpan> spans,
            String replacement, boolean contactData, int priority, String identity) {
        for (TextSpan span : spans) {
            target.add(new ReplacementSpan(span.start(), span.end(), replacement, contactData, priority, identity));
        }
    }

    /**
     * Redacts the union of overlapping or adjacent source spans once. A primary match covering
     * the entire region keeps its token, including contained surname matches; equal spans prefer
     * literal primary token over aliases and residual redaction. Equal-span literal collisions
     * redact instead of depending on registration order. Overlapping or adjacent occurrences of one
     * identifier coalesce into a single token, because every character of that union belongs to
     * an occurrence of the same value. A union that extends across two different replacements,
     * and every contact-data union, redacts instead.
     * Source offsets, never canonical identifier lengths, determine coverage and precedence.
     */
    private static List<ReplacementSpan> mergeSensitiveSpans(List<ReplacementSpan> spans) {
        spans.sort(Comparator.comparingInt(ReplacementSpan::start)
                .thenComparing(Comparator.comparingInt(ReplacementSpan::end).reversed())
                .thenComparing(Comparator.comparingInt(ReplacementSpan::priority).reversed())
                .thenComparing(span -> REDACTED.equals(span.replacement())));
        List<ReplacementSpan> merged = new ArrayList<>();
        for (ReplacementSpan next : spans) {
            if (merged.isEmpty() || merged.getLast().end() < next.start()) {
                merged.add(next);
                continue;
            }
            ReplacementSpan previous = merged.getLast();
            boolean contactData = previous.contactData() || next.contactData();
            boolean extendsRegion = next.end() > previous.end();
            boolean sameReplacement = previous.replacement().equals(next.replacement());
            boolean ambiguous = previous.start() == next.start() && previous.end() == next.end()
                    && previous.priority() > 0 && previous.priority() == next.priority()
                    && !previous.identity().equals(next.identity());
            String replacement = ambiguous || !sameReplacement && (contactData || extendsRegion)
                    ? REDACTED : previous.replacement();
            merged.set(merged.size() - 1, new ReplacementSpan(previous.start(),
                    Math.max(previous.end(), next.end()), replacement, contactData,
                    previous.priority(), previous.identity()));
        }
        return merged;
    }

    /** Matches only the canonical form, measuring boundaries on complete source code points. */
    private static List<TextSpan> identifierSpans(
            String text, CanonicalText.Projection projection, String value) {
        boolean bounded = usesAsciiWordBoundary(value);
        List<TextSpan> spans = new ArrayList<>();
        String canonical = projection.value();
        for (int start = canonical.indexOf(value); start >= 0;
                start = canonical.indexOf(value, start + 1)) {
            int sourceStart = projection.sourceStart(start);
            int sourceEnd = projection.sourceEnd(start + value.length());
            if (!bounded
                    || (sourceStart == 0 || !isPrimaryIdentifierWord(text.codePointBefore(sourceStart)))
                    && (sourceEnd == text.length() || !isPrimaryIdentifierWord(text.codePointAt(sourceEnd)))) {
                spans.add(new TextSpan(sourceStart, sourceEnd));
            }
        }
        return spans;
    }

    /** Mirrors the primary pattern's Latin-script, number and underscore boundary. */
    private static boolean isPrimaryIdentifierWord(int codePoint) {
        int type = Character.getType(codePoint);
        return codePoint == '_' || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                || type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /**
     * Whether the residual pattern may run for an identifier: measured with the outbound leak
     * scan's own normalization so replacement coverage and scan coverage cannot drift, and never
     * for a value the redaction sentinels themselves contain.
     */
    private static boolean residualEligible(String scanNormalized) {
        return scanNormalized.length() >= OutboundLeakScan.MIN_IDENTIFIER_LENGTH
                && !OutboundLeakScan.normalizeForScan(REDACTED).contains(scanNormalized)
                && !OutboundLeakScan.normalizeForScan(OMITTED_BY_POLICY).contains(scanNormalized);
    }

    /**
     * Whether the replacer anchors this value on an ASCII word boundary, shared with the seeding
     * gate so admission and replacement bound a value the same way.
     * @param value NFKC-normalized identifier value
     * @return true when the value both begins and ends with an ASCII letter or digit
     */
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
