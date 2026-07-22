package ooo.klae.connex.backend.ai.brief;

import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/**
 * Masked prompt and request-local re-identification context for one deal brief.
 * @param context request-local masking context
 * @param prompt provider-ready masked prompt
 * @param contributorPersonIds exact directly contributing contact ids captured before invocation
 */
public record BriefAssembly(
        MaskingContext context, MaskedPrompt prompt, List<Integer> contributorPersonIds) {

    public BriefAssembly {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(prompt, "prompt");
        contributorPersonIds = List.copyOf(
                Objects.requireNonNull(contributorPersonIds, "contributorPersonIds"));
    }
}
