package ooo.klae.connex.backend.ai.masking;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared literal and conversation-label projections of original identifier/text values. Every
 * projected UTF-16 unit retains its complete original source span through normalization,
 * delimiter deletion, separator collapse and case folding. Literal matching never strips links.
 * The additional label projection composes link-removal offsets, identically on both operands;
 * it supplies lookup aliases without discarding matches in the original literal projection.
 */
final class CanonicalText {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private CanonicalText() {
    }

    /** Returns the original text projected into the shared literal canonical form. */
    static String canonical(String raw) {
        return project(raw).value();
    }

    /**
     * Prepares display text once, retaining case, spacing and every character the provider should
     * still receive. Applying this method to its own result returns that result unchanged.
     */
    static String prepare(String raw) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder prepared = new StringBuilder(normalized.length());
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isDefaultIgnorable(codePoint)) {
                continue;
            }
            if (isSeparator(codePoint)) {
                prepared.append(' ');
            } else if (cancelsPrecedingDelimiter(prepared, codePoint)) {
                prepared.setLength(prepared.length() - 1);
            } else {
                prepared.appendCodePoint(codePoint);
            }
        }
        return Normalizer.normalize(prepared, Normalizer.Form.NFKC);
    }

    /** Projects the original text, retaining offsets across canonical preparation. */
    static Projection project(String raw) {
        return project(prepareProjection(raw));
    }

    /** Reuses the source map already built for contact and display preparation. */
    static Projection project(Projection prepared) {
        return project(prepared, false);
    }

    /** Projects label aliases on either operand with offsets composed back to original text. */
    static Projection projectLabels(String raw) {
        return projectLabels(project(raw));
    }

    /** Reuses the literal projection; ordinary prose needs no additional offset maps. */
    static Projection projectLabels(Projection literal) {
        if (literal.value().indexOf('[') < 0 && literal.value().indexOf(']') < 0) {
            return literal;
        }
        return project(ConversationText.projectLabels(literal), true);
    }

    /** Retains literal coverage without exposing any partially transformed stored-name alias. */
    static StoredIdentifier storedIdentifier(String raw) {
        Projection literal = project(raw);
        if (literal.value().indexOf('[') < 0 && literal.value().indexOf(']') < 0) {
            return new StoredIdentifier(literal.value(), literal.value(), true);
        }
        Optional<Projection> labels = ConversationText.storedLabels(literal);
        return new StoredIdentifier(literal.value(),
                labels.map(value -> project(value, true).value()).orElse(""), labels.isPresent());
    }

    /** Exhausted stored names remain literal-sensitive but cannot supply display aliases. */
    record StoredIdentifier(String literal, String label, boolean converged) {
    }

    /** Prepares contact/display text while retaining a map to the original source. */
    static Projection prepareProjection(String raw) {
        int[] starts = new int[raw.length()];
        int[] ends = new int[raw.length()];
        for (int offset = 0; offset < raw.length();) {
            int end = offset + Character.charCount(raw.codePointAt(offset));
            Arrays.fill(starts, offset, end, offset);
            Arrays.fill(ends, offset, end, end);
            offset = end;
        }
        Projection normalized = expandCompatibility(raw, starts, ends);
        String prepared = prepare(normalized.value());
        if (prepared.equals(normalized.value())) {
            return normalized;
        }
        StringBuilder value = new StringBuilder(normalized.value().length());
        int[] preparedStarts = new int[normalized.value().length()];
        int[] preparedEnds = new int[normalized.value().length()];
        for (int offset = 0; offset < normalized.value().length();) {
            int codePoint = normalized.value().codePointAt(offset);
            int end = offset + Character.charCount(codePoint);
            if (!isDefaultIgnorable(codePoint)) {
                if (cancelsPrecedingDelimiter(value, codePoint)) {
                    value.setLength(value.length() - 1);
                } else {
                    int start = value.length();
                    value.appendCodePoint(isSeparator(codePoint) ? ' ' : codePoint);
                    Arrays.fill(preparedStarts, start, value.length(), normalized.sourceStart(offset));
                    Arrays.fill(preparedEnds, start, value.length(), normalized.sourceEnd(end));
                }
            }
            offset = end;
        }
        return compose(value.toString(), preparedStarts, preparedEnds);
    }

    /**
     * Compatibility expansion precedes grapheme composition: compatibility Hangul letters can
     * become combining Jamo across original grapheme boundaries. Expanding each code point first
     * exposes those boundaries before composition, while each expansion retains its source span.
     */
    private static Projection expandCompatibility(String raw, int[] starts, int[] ends) {
        if (Normalizer.isNormalized(raw, Normalizer.Form.NFKC)) {
            return new Projection(raw, starts, ends);
        }
        StringBuilder expanded = new StringBuilder(raw.length());
        for (int offset = 0; offset < raw.length();) {
            int end = offset + Character.charCount(raw.codePointAt(offset));
            String unit = Normalizer.normalize(raw.substring(offset, end), Normalizer.Form.NFKC);
            int start = expanded.length();
            expanded.append(unit);
            if (expanded.length() > starts.length) {
                int capacity = Math.max(expanded.length(), starts.length * 2);
                starts = Arrays.copyOf(starts, capacity);
                ends = Arrays.copyOf(ends, capacity);
            }
            Arrays.fill(starts, start, expanded.length(), offset);
            Arrays.fill(ends, start, expanded.length(), end);
            offset = end;
        }
        return compose(expanded.toString(), starts, ends);
    }

    private static Projection project(Projection source, boolean labels) {
        String prepared = source.value();
        if (!labels && prepared.indexOf('{') < 0 && prepared.indexOf('}') < 0
                && !prepared.startsWith(" ") && !prepared.endsWith(" ") && !prepared.contains("  ")) {
            Projection folded = contextFreeFold(source);
            return compose(folded.value(), folded.starts(), folded.ends());
        }
        StringBuilder collapsed = new StringBuilder(prepared.length());
        int[] starts = new int[prepared.length()];
        int[] ends = new int[prepared.length()];
        int labelStart = -1;
        for (int offset = 0; offset < prepared.length();) {
            int codePoint = prepared.codePointAt(offset);
            int end = offset + Character.charCount(codePoint);
            if (labels && codePoint == '[') {
                if (labelStart < 0) {
                    labelStart = offset;
                }
                offset = end;
                continue;
            }
            if (labels && codePoint == ']') {
                if (!collapsed.isEmpty()) {
                    ends[collapsed.length() - 1] = source.sourceEnd(end);
                }
                offset = end;
                continue;
            }
            if (isDelimiter(codePoint)) {
                offset = end;
                continue;
            }
            if (codePoint == ' ' && (collapsed.isEmpty() || collapsed.charAt(collapsed.length() - 1) == ' ')) {
                if (!collapsed.isEmpty()) {
                    ends[collapsed.length() - 1] = source.sourceEnd(end);
                }
            } else {
                int start = collapsed.length();
                collapsed.appendCodePoint(codePoint);
                Arrays.fill(starts, start, collapsed.length(), source.sourceStart(labelStart < 0 ? offset : labelStart));
                Arrays.fill(ends, start, collapsed.length(), source.sourceEnd(end));
            }
            labelStart = -1;
            offset = end;
        }
        if (!collapsed.isEmpty() && collapsed.charAt(collapsed.length() - 1) == ' ') {
            collapsed.setLength(collapsed.length() - 1);
        }
        Projection composed = compose(collapsed.toString(), starts, ends);
        Projection folded = contextFreeFold(composed);
        return compose(folded.value(), folded.starts(), folded.ends());
    }

    /**
     * Normalization cannot cross an extended grapheme boundary. When brace deletion exposes a
     * composition or combining-mark reorder, each normalized grapheme maps to its entire source
     * span so replacement cannot retain a fragment or split a surrogate pair. Already normalized
     * text retains the more precise code-point offsets without allocating per-grapheme strings.
     */
    private static Projection compose(String source, int[] starts, int[] ends) {
        if (Normalizer.isNormalized(source, Normalizer.Form.NFKC)) {
            return new Projection(source, starts, ends);
        }
        String normalized = Normalizer.normalize(source, Normalizer.Form.NFKC);
        int[] composedStarts = new int[normalized.length()];
        int[] composedEnds = new int[normalized.length()];
        Matcher graphemes = GRAPHEME.matcher(source);
        int offset = 0;
        while (graphemes.find()) {
            int length = Normalizer.normalize(graphemes.group(), Normalizer.Form.NFKC).length();
            Arrays.fill(composedStarts, offset, offset + length, starts[graphemes.start()]);
            Arrays.fill(composedEnds, offset, offset + length, ends[graphemes.end() - 1]);
            offset += length;
        }
        return new Projection(normalized, composedStarts, composedEnds);
    }

    /**
     * Matches Unicode case-insensitive regex equivalence with one constant-time mapping per code
     * point. Uppercase then lowercase unifies dotted/dotless I and all sigma forms without string
     * casing's contextual scans or expansions. Deleting a combining dot immediately after folded
     * i also unifies decomposed dotted I; extending the preceding source span over that deletion
     * prevents replacement from leaving its combining mark behind. Each retained code point keeps
     * its complete source span, including supplementary letters after a deletion.
     */
    private static Projection contextFreeFold(Projection source) {
        StringBuilder folded = new StringBuilder(source.value().length());
        int[] starts = source.starts();
        int[] ends = source.ends();
        boolean copiedOffsets = false;
        for (int offset = 0; offset < source.value().length();) {
            int codePoint = source.value().codePointAt(offset);
            int end = offset + Character.charCount(codePoint);
            if (codePoint == 0x0307 && !folded.isEmpty() && folded.charAt(folded.length() - 1) == 'i') {
                if (!copiedOffsets) {
                    starts = Arrays.copyOf(starts, starts.length);
                    ends = Arrays.copyOf(ends, ends.length);
                    copiedOffsets = true;
                }
                ends[folded.length() - 1] = source.sourceEnd(end);
            } else {
                int start = folded.length();
                folded.appendCodePoint(Character.toLowerCase(Character.toUpperCase(codePoint)));
                if (folded.length() != end && !copiedOffsets) {
                    starts = Arrays.copyOf(starts, source.value().length());
                    ends = Arrays.copyOf(ends, source.value().length());
                    copiedOffsets = true;
                }
                if (copiedOffsets) {
                    Arrays.fill(starts, start, folded.length(), source.sourceStart(offset));
                    Arrays.fill(ends, start, folded.length(), source.sourceEnd(end));
                }
            }
            offset = end;
        }
        String value = folded.toString();
        return value.equals(source.value()) ? source : new Projection(value, starts, ends);
    }

    /**
     * Whether this brace cancels an identical brace immediately before it. That removes exactly
     * the doubled delimiters an injected placeholder would need, and nothing else: the prepared
     * text can therefore never contain a placeholder the request did not issue, while single
     * braces stay in the text the provider receives.
     */
    private static boolean cancelsPrecedingDelimiter(StringBuilder prepared, int codePoint) {
        return isDelimiter(codePoint)
                && !prepared.isEmpty()
                && prepared.charAt(prepared.length() - 1) == codePoint;
    }

    private static boolean isDelimiter(int codePoint) {
        return codePoint == '{' || codePoint == '}';
    }

    /** The separator class the mapper's SQL prefilter folds on both operands. */
    private static boolean isSeparator(int codePoint) {
        return Character.getType(codePoint) == Character.CONTROL
                || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    /**
     * Unicode Default_Ignorable_Code_Point. These render as nothing and are read straight through
     * by a model, so leaving one inside a stored name would hide that name from replacement,
     * residual redaction, seeding admission and the outbound scan at the same time. Deleting them
     * here gives all four consumers the same blindness-free view; the SQL prefilter keeps such a
     * candidate through its non-ASCII fallback, because every ignorable code point is non-ASCII.
     */
    private static boolean isDefaultIgnorable(int codePoint) {
        return codePoint == 0x00AD || codePoint == 0x034F || codePoint == 0x061C
                || codePoint == 0x115F || codePoint == 0x1160
                || codePoint == 0x17B4 || codePoint == 0x17B5
                || codePoint >= 0x180B && codePoint <= 0x180F
                || codePoint >= 0x200B && codePoint <= 0x200F
                || codePoint >= 0x202A && codePoint <= 0x202E
                || codePoint >= 0x2060 && codePoint <= 0x206F
                || codePoint == 0x3164
                || codePoint >= 0xFE00 && codePoint <= 0xFE0F
                || codePoint == 0xFEFF
                || codePoint == 0xFFA0
                || codePoint >= 0xFFF0 && codePoint <= 0xFFF8
                || codePoint >= 0x1BCA0 && codePoint <= 0x1BCA3
                || codePoint >= 0x1D173 && codePoint <= 0x1D17A
                || codePoint >= 0xE0000 && codePoint <= 0xE0FFF;
    }

    /** Every UTF-16 code unit maps to its complete source code point or composed grapheme. */
    record Projection(String value, int[] starts, int[] ends) {
        int sourceStart(int offset) {
            return starts[offset];
        }

        int sourceEnd(int end) {
            return ends[end - 1];
        }
    }
}
