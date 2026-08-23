package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The truthful breadth statement shown before a broad assistant request runs.
 *
 * <p>Every number here comes from the same interpretation and the same caps the turn will execute,
 * so the sentence the member confirms is the query the server performs.
 *
 * <p>Property inclusion is pinned to ALWAYS because {@code skillKey} is absent for requests the
 * generic loop will handle, and the browser must be able to tell that apart from an unknown value.
 *
 * @param scope exact interpreted scope, including the evaluated cohort size
 * @param skillKey declared skill that would run, or null when the generic loop would handle it
 * @param skillVersion semantic version of that declaration, or null
 * @param confirmationRecommended whether the declared threshold asks for confirmation first
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatScopePreviewDto(
        AiChatQueryScopeDto scope,
        String skillKey,
        String skillVersion,
        boolean confirmationRecommended) {
}
