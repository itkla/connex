package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Objects;

/**
 * One declared skill the calling member can actually run, as a contextual entry point renders it.
 *
 * <p>This is the public half of the skill contract and nothing more. The retrieval plan, tool
 * allow-list, recognition triggers, per-turn directive, bounds, and budgets stay server-side: they
 * are the reason routine work is not improvised, and publishing them would invite a client to
 * reproduce or steer the plan the server owns.
 *
 * <p>Copy is never rendered here. {@code nameKey} and {@code descriptionKey} are i18n keys the
 * client authors EN and JA copy under, so the product language for an entry point is owned by the
 * locale bundles rather than by an English string baked into a response.
 *
 * <p>Every property is non-null by construction, so the instance-wide {@code non_null} inclusion
 * default can never drop one and a strict client always sees the same key set.
 *
 * @param key stable additive catalog key
 * @param version semantic version of the declaration this entry point was built against
 * @param nameKey client i18n key for the product-language name
 * @param descriptionKey client i18n key for the product-language description
 * @param contextKinds record kinds the skill can be anchored to, alphabetically ordered
 * @param needsSubject whether the skill refuses without an anchoring record
 * @param authority lower-case ceiling on what the skill may do
 */
public record AiAssistantSkillDto(
        String key,
        String version,
        String nameKey,
        String descriptionKey,
        List<String> contextKinds,
        boolean needsSubject,
        String authority) {

    public AiAssistantSkillDto {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(nameKey, "nameKey");
        Objects.requireNonNull(descriptionKey, "descriptionKey");
        Objects.requireNonNull(authority, "authority");
        contextKinds = List.copyOf(contextKinds);
    }
}
