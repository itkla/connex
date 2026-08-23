package ooo.klae.connex.backend.ai.assistant;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.SkillSpec;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Deterministic recognition of the routine CRM jobs the skill catalog owns.
 *
 * <p>Routing is pattern- and context-driven on the server, never a model decision, so the same
 * request always selects the same skill and version and the choice can be audited afterwards. When
 * nothing matches — or a matched skill has no record to anchor to, is not implemented in this
 * build, or the asking member lacks its declared permissions — the router declines and the bounded
 * generic tool loop handles the turn. Declining is deliberate: a novel question answered by the
 * fallback is a better outcome than a routine skill refusing work the generic loop could do.
 */
@Component
@RequiredArgsConstructor
public class AiSkillRouter {
    /** Stable machine reasons for a routing outcome, recorded but never shown to a viewer. */
    public static final String MATCHED = "skill_matched";
    public static final String NO_MATCH = "no_matching_skill";
    public static final String MISSING_CONTEXT = "missing_required_context";
    public static final String UNAVAILABLE = "skill_not_yet_implemented";
    public static final String PERMISSION_DENIED = "skill_permission_denied";

    private static final int MAX_MATCHED_TEXT_CHARS = 2_000;

    private final AiSkillCatalog skillCatalog;
    private final WorkspaceService workspaceService;

    /** One record the selected skill is anchored to. */
    public record Subject(String kind, int id) {
    }

    /**
     * One routing decision.
     *
     * @param skill selected skill, or null when the generic loop handles the turn
     * @param reason stable machine reason for the decision
     * @param subject anchoring record, or null when the skill is cohort-scoped
     * @param previewRecommended whether the declared scope threshold asks for a scope preview
     */
    public record Routing(SkillSpec skill, String reason, Subject subject,
            boolean previewRecommended) {

        /** @return whether a server-owned skill plan will run for this turn */
        public boolean routed() {
            return skill != null;
        }

        /** Returns the decision to let the bounded generic loop handle the turn. */
        public static Routing fallback(String reason) {
            return new Routing(null, reason, null, false);
        }
    }

    /**
     * Selects the skill that owns one request.
     *
     * @param workspaceId active workspace
     * @param userId asking member
     * @param requestText the member's literal request
     * @param context records the turn is anchored to, most relevant first
     * @param scope validated declared query scope
     * @return the routing decision
     */
    public Routing route(
            int workspaceId,
            int userId,
            String requestText,
            List<AiChatPageContextDto> context,
            AiChatQueryScope scope) {
        if (requestText == null || requestText.isBlank()) {
            return Routing.fallback(NO_MATCH);
        }
        String text = requestText.length() > MAX_MATCHED_TEXT_CHARS
                ? requestText.substring(0, MAX_MATCHED_TEXT_CHARS)
                : requestText;
        for (SkillSpec spec : skillCatalog.skills()) {
            if (!matches(spec, text)) {
                continue;
            }
            if (!spec.available()) {
                return Routing.fallback(UNAVAILABLE);
            }
            if (!permitted(workspaceId, userId, spec.permissions())) {
                return Routing.fallback(PERMISSION_DENIED);
            }
            Subject subject = subject(spec, context);
            if (spec.needsSubject() && subject == null) {
                return Routing.fallback(MISSING_CONTEXT);
            }
            return new Routing(spec, MATCHED, subject, previewRecommended(spec, scope));
        }
        return Routing.fallback(NO_MATCH);
    }

    private static boolean matches(SkillSpec spec, String text) {
        for (Pattern trigger : spec.triggers()) {
            if (trigger.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean permitted(int workspaceId, int userId, Set<Permission> required) {
        if (required.isEmpty()) {
            return true;
        }
        return workspaceService.permissionsFor(workspaceId, userId).containsAll(required);
    }

    private static Subject subject(SkillSpec spec, List<AiChatPageContextDto> context) {
        if (context == null || spec.contextKinds().isEmpty()) {
            return null;
        }
        for (AiChatPageContextDto candidate : context) {
            if (candidate != null && candidate.id() > 0
                    && spec.contextKinds().contains(candidate.kind())) {
                return new Subject(candidate.kind(), candidate.id());
            }
        }
        return null;
    }

    /**
     * A cohort skill asks for a scope preview once its declared threshold is plausibly crossed. The
     * threshold is evaluated against the declared scope alone, because counting the real cohort
     * before the turn starts would double the retrieval the preview exists to bound.
     */
    private static boolean previewRecommended(SkillSpec spec, AiChatQueryScope scope) {
        return spec.scopePreviewRecords() != Integer.MAX_VALUE
                && !spec.needsSubject()
                && !scope.constrainsCohort();
    }
}
