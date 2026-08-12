package ooo.klae.connex.backend.ai;

import java.util.Objects;

/** Ephemeral masked provider output used only to request one schema repair. */
public record AiStructuredRepair(String schemaRule, String offendingOutput, boolean truncated) {
    private static final int MAX_REPAIR_OUTPUT_CHARS = 16_000;

    public AiStructuredRepair {
        schemaRule = Objects.requireNonNull(schemaRule, "schemaRule");
        offendingOutput = Objects.requireNonNull(offendingOutput, "offendingOutput");
    }

    /** Creates a bounded repair payload without retaining an unbounded provider response. */
    public static AiStructuredRepair from(String schemaRule, String offendingOutput) {
        String output = Objects.requireNonNull(offendingOutput, "offendingOutput");
        if (output.length() <= MAX_REPAIR_OUTPUT_CHARS) {
            return new AiStructuredRepair(schemaRule, output, false);
        }
        return new AiStructuredRepair(
                schemaRule,
                output.substring(output.length() - MAX_REPAIR_OUTPUT_CHARS),
                true);
    }

    @Override
    public String toString() {
        return "AiStructuredRepair[schemaRule=" + schemaRule
                + ", offendingOutput=<redacted>, truncated=" + truncated + "]";
    }
}
