package ooo.klae.connex.backend.ai;

import java.util.HashSet;
import java.util.List;

import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Closed v1 administrative ceiling; configuration cannot admit a tool outside this set. */
public final class AiStandingApprovalCeiling {
    private static final List<String> TOOLS = List.of(
            "update_record_fields", "remove_tag", "set_response_due", "change_deal_stage");

    private AiStandingApprovalCeiling() {
    }

    /** Validates finite per-approval caps and an exact subset of the reviewed tool surface. */
    public static int validate(AiStandingApprovalPolicyRequest request) {
        if (request == null || request.enabled() == null || request.allowedTools() == null
                || request.allowedTools().size() > TOOLS.size()
                || new HashSet<>(request.allowedTools()).size() != request.allowedTools().size()
                || request.maximumDailyCap() == null || request.maximumDailyCap() < 1
                || request.maximumDailyCap() > 10000 || request.maximumPerRunCap() == null
                || request.maximumPerRunCap() < 1
                || request.maximumPerRunCap() > request.maximumDailyCap()) {
            throw invalid();
        }
        int mask = 0;
        for (String tool : request.allowedTools()) {
            int index = tool == null ? -1 : TOOLS.indexOf(tool);
            if (index < 0) {
                throw invalid();
            }
            mask |= 1 << index;
        }
        version(request.expectedStateVersion());
        return mask;
    }

    /** Decodes stored configuration strictly, refusing unknown future bits. */
    public static List<String> tools(int mask) {
        if (mask < 0 || mask > 15) {
            throw new IllegalStateException("Unknown standing approval tool policy");
        }
        return java.util.stream.IntStream.range(0, TOOLS.size())
                .filter(index -> (mask & (1 << index)) != 0).mapToObj(TOOLS::get).toList();
    }

    /** Parses the lossless HTTP version without accepting signs, whitespace or zero. */
    public static long version(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    /** Advances authority without wrapping or restoring an earlier generation. */
    public static long nextEpoch(long value) {
        if (value < 1 || value == Long.MAX_VALUE) {
            throw new IllegalStateException("Standing approval authority epoch exhausted");
        }
        return Math.incrementExact(value);
    }

    private static BadRequestException invalid() {
        return new BadRequestException("Standing approval policy is invalid");
    }
}
