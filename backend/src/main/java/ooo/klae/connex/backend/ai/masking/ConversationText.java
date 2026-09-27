package ooo.klae.connex.backend.ai.masking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;

/**
 * Record-link display cleanup and source-mapped lookup aliases. Display cleanup runs only after
 * sensitive spans have been replaced. The alias projection preserves original coverage and is
 * shared by registration, admission, masking and scanning; link targets never grant authority.
 */
public final class ConversationText {
    private static final int MAX_PREPROCESSING_PASSES = 16;
    private static final Pattern ISSUED_TOKEN = Pattern.compile("\\u0000([A-Z][1-9][0-9]*)\\u0000");

    private ConversationText() {
    }

    /** Returns stable display text; callers must mask original text before applying this rewrite. */
    public static String preprocess(String text) {
        String prepared = CanonicalText.prepare(Objects.requireNonNull(text, "text"));
        Transformation<String> result = preprocessToFixedPoint(prepared);
        return result.converged() ? result.value() : MaskingEngine.OMITTED_BY_POLICY;
    }

    private static Transformation<String> preprocessToFixedPoint(String prepared) {
        for (int pass = 0; pass < MAX_PREPROCESSING_PASSES; pass++) {
            String next = CanonicalText.prepare(stripRecordLinks(prepared));
            if (next.equals(prepared)) {
                return new Transformation<>(next, true);
            }
            prepared = next;
        }
        return new Transformation<>(prepared,
                CanonicalText.prepare(stripRecordLinks(prepared)).equals(prepared));
    }

    /**
     * Finishes text whose raw segments have already had controls removed. Only emitted tokens
     * carry the reserved NUL framing. Any remaining structural rewrite was not covered by the
     * completed matching/source-cleanup passes and refuses the value before truncation or egress.
     */
    static String finishMasked(String text) {
        if (!prepareAroundIssuedTokens(text).equals(text) || !stripRecordLinks(text).equals(text)) {
            throw new MaskingLeakException("Outbound AI text requires an unscreened structural rewrite");
        }
        return ISSUED_TOKEN.matcher(text).replaceAll("{{$1}}");
    }

    private static String prepareAroundIssuedTokens(String text) {
        StringBuilder prepared = new StringBuilder(text.length());
        Matcher tokens = ISSUED_TOKEN.matcher(text);
        int copied = 0;
        while (tokens.find()) {
            prepared.append(CanonicalText.prepare(text.substring(copied, tokens.start())));
            prepared.append(tokens.group());
            copied = tokens.end();
        }
        return prepared.append(CanonicalText.prepare(text.substring(copied))).toString();
    }

    /** Finds original link syntax independently of sensitive replacements that may consume a bracket. */
    static List<SourceSpan> linkSyntax(CanonicalText.Projection literal) {
        List<SourceSpan> removed = new ArrayList<>();
        projectToFixedPoint(literal, false, removed).requireConverged();
        return List.copyOf(removed);
    }

    record SourceSpan(int start, int end) {
    }

    /** Composes every removed link's coverage into the surviving label's original offsets. */
    static CanonicalText.Projection projectLabels(CanonicalText.Projection source) {
        return projectToFixedPoint(source, true, new ArrayList<>()).requireConverged();
    }

    /** Stored identifiers may lose their alias locally; outgoing text still requires convergence. */
    static Optional<CanonicalText.Projection> storedLabels(CanonicalText.Projection source) {
        Transformation<CanonicalText.Projection> result = projectToFixedPoint(source, true, new ArrayList<>());
        return result.converged() ? Optional.of(result.value()) : Optional.empty();
    }

    /** The extra probe proves convergence at the bound without accepting another transformation. */
    private static Transformation<CanonicalText.Projection> projectToFixedPoint(
            CanonicalText.Projection source, boolean expandCoverage, List<SourceSpan> removed) {
        CanonicalText.Projection projected = source;
        for (int pass = 0; pass < MAX_PREPROCESSING_PASSES; pass++) {
            CanonicalText.Projection next = stripRecordLinks(projected, expandCoverage, removed);
            if (next.value().equals(projected.value())) {
                return new Transformation<>(next, true);
            }
            projected = next;
        }
        return new Transformation<>(projected, stripRecordLinks(projected).value().equals(projected.value()));
    }

    /** A bounded transformation cannot expose a partially transformed value to a security consumer. */
    private record Transformation<T>(T value, boolean converged) {
        T requireConverged() {
            if (!converged) {
                throw new MaskingLeakException("Outbound AI text transformation did not converge");
            }
            return value;
        }
    }

    private static String stripRecordLinks(String text) {
        if (text.indexOf('[') < 0) {
            return text;
        }
        int[] starts = new int[text.length()];
        int[] ends = new int[text.length()];
        for (int offset = 0; offset < text.length(); offset++) {
            starts[offset] = offset;
            ends[offset] = offset + 1;
        }
        return stripRecordLinks(new CanonicalText.Projection(text, starts, ends)).value();
    }

    /** Reads each label/target once per bounded pass, composing offsets across nested links. */
    private static CanonicalText.Projection stripRecordLinks(CanonicalText.Projection source) {
        return stripRecordLinks(source, true, new ArrayList<>());
    }

    private static CanonicalText.Projection stripRecordLinks(
            CanonicalText.Projection source, boolean expandCoverage, List<SourceSpan> removed) {
        String text = source.value();
        if (text.indexOf('[') < 0) {
            return source;
        }
        StringBuilder stripped = new StringBuilder(text.length());
        int[] starts = new int[text.length()];
        int[] ends = new int[text.length()];
        int labelStart = -1;
        int copied = 0;
        int targetEnd = text.indexOf(')');
        for (int offset = 0; offset < text.length(); offset++) {
            char current = text.charAt(offset);
            if (current == '[' && labelStart < 0) {
                labelStart = offset;
            } else if (current == ']' && labelStart >= 0) {
                if (isRecordTarget(text, offset + 1)) {
                    while (targetEnd >= 0 && targetEnd < offset + 2) {
                        targetEnd = text.indexOf(')', targetEnd + 1);
                    }
                    if (targetEnd >= 0) {
                        removed.add(new SourceSpan(source.sourceStart(labelStart), source.sourceEnd(labelStart + 1)));
                        removed.add(new SourceSpan(source.sourceStart(offset), source.sourceEnd(targetEnd + 1)));
                        appendSource(stripped, starts, ends, source, copied, labelStart);
                        int start = stripped.length();
                        appendSource(stripped, starts, ends, source, labelStart + 1, offset);
                        if (expandCoverage && stripped.length() > start) {
                            starts[start] = source.sourceStart(labelStart);
                            ends[stripped.length() - 1] = source.sourceEnd(targetEnd + 1);
                        } else if (expandCoverage && !stripped.isEmpty()) {
                            ends[stripped.length() - 1] = source.sourceEnd(targetEnd + 1);
                        }
                        copied = targetEnd + 1;
                        offset = targetEnd;
                    }
                }
                labelStart = -1;
            }
        }
        appendSource(stripped, starts, ends, source, copied, text.length());
        return new CanonicalText.Projection(stripped.toString(),
                Arrays.copyOf(starts, stripped.length()), Arrays.copyOf(ends, stripped.length()));
    }

    private static void appendSource(StringBuilder target, int[] starts, int[] ends,
            CanonicalText.Projection source, int start, int end) {
        int offset = target.length();
        target.append(source.value(), start, end);
        System.arraycopy(source.starts(), start, starts, offset, end - start);
        System.arraycopy(source.ends(), start, ends, offset, end - start);
    }

    private static boolean isRecordTarget(String text, int offset) {
        return text.startsWith("(person:", offset) || text.startsWith("(company:", offset)
                || text.startsWith("(deal:", offset) || text.startsWith("(record:", offset);
    }
}
