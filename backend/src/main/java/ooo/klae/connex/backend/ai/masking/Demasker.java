package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-identifies provider output by replacing issued request-local placeholders with their original
 * display values. Unknown placeholder-shaped references are replaced with a fixed marker and
 * counted as warnings so callers can discard low-integrity output.
 */
public final class Demasker {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{\\s*([A-Z][1-9][0-9]*)\\s*}}");

    private Demasker() {
    }

    /**
     * Demasked provider output.
     * @param text output after token replacement
     * @param warnings number of unknown placeholder references encountered
     */
    public record DemaskResult(String text, int warnings) {

        public DemaskResult {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Replaces issued placeholders such as {@code {{P1}}} and {@code {{ P1 }}}. Bare identifiers
     * such as {@code P1} are never replaced.
     * @param modelOutput provider output
     * @param ctx request-local masking context
     * @return demasked text plus warning count
     */
    public static DemaskResult demask(String modelOutput, MaskingContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        if (modelOutput == null || modelOutput.isBlank()) {
            return new DemaskResult("", 0);
        }
        Matcher matcher = TOKEN_PATTERN.matcher(modelOutput);
        StringBuilder demasked = new StringBuilder();
        int lastEnd = 0;
        int warnings = 0;
        while (matcher.find()) {
            demasked.append(modelOutput, lastEnd, matcher.start());
            String token = "{{" + matcher.group(1) + "}}";
            String originalValue = ctx.originalValueForToken(token);
            if (originalValue == null) {
                demasked.append("[unknown reference]");
                warnings++;
            } else {
                demasked.append(originalValue);
            }
            lastEnd = matcher.end();
        }
        demasked.append(modelOutput, lastEnd, modelOutput.length());
        return new DemaskResult(demasked.toString(), warnings);
    }
}
