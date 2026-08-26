package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.Availability;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.PlanStep;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.SkillSpec;
import ooo.klae.connex.backend.tenant.Permission;

class AiSkillCatalogTest {

    /**
     * The first-contract skill keys from the parent issue. They are asserted as a set, not as a
     * count, so a key can only ever be added — renaming or removing one would strand a persisted
     * turn that already names it.
     */
    private static final Set<String> DECLARED_KEYS = Set.of(
            "relationship_brief_v1",
            "relationship_change_summary_v1",
            "relationship_cooling_explanation_v1",
            "introduction_path_explanation_v1",
            "meeting_preparation_v1",
            "meeting_follow_up_extraction_v1",
            "follow_up_draft_v1",
            "activity_digest_v1",
            "deal_risk_review_v1",
            "pipeline_attention_review_v1",
            "stakeholder_gap_analysis_v1",
            "company_review_v1",
            "daily_work_brief_v1",
            "commitment_extraction_v1",
            "data_quality_review_v1",
            "natural_language_report_v1");

    private static final Set<String> AVAILABLE_KEYS = Set.of(
            "relationship_cooling_explanation_v1",
            "activity_digest_v1",
            "relationship_brief_v1",
            "pipeline_attention_review_v1",
            "daily_work_brief_v1");

    private final AiSkillCatalog catalog = new AiSkillCatalog();

    @Test
    void everyFirstContractKeyIsDeclaredAndKeysStayAdditive() {
        Set<String> keys = catalog.skills().stream()
                .map(SkillSpec::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertTrue(keys.containsAll(DECLARED_KEYS),
                "A first-contract skill key is missing from the catalog");
        assertEquals(DECLARED_KEYS.size(), keys.size(),
                "An undeclared skill key entered the catalog");
    }

    @Test
    void reservedKeysAreDeclaredWithAStableReasonAndNoExecutablePlan() {
        for (SkillSpec spec : catalog.skills()) {
            if (AVAILABLE_KEYS.contains(spec.key())) {
                continue;
            }
            assertEquals(Availability.DECLARED, spec.availability(),
                    () -> spec.key() + " must be declared, not available");
            assertEquals("skill_not_yet_implemented", spec.unavailableReason(),
                    () -> spec.key() + " must carry a stable unavailable reason");
            assertTrue(spec.plan().isEmpty(),
                    () -> spec.key() + " must not declare a plan it cannot run");
            assertFalse(catalog.isAvailable(spec.key()),
                    () -> spec.key() + " must not be routable");
            assertTrue(spec.triggers().isEmpty(),
                    () -> spec.key() + " must not be recognizable while unimplemented");
        }
    }

    @Test
    void everyAvailableSkillDeclaresACompleteExecutableContract() {
        for (String key : AVAILABLE_KEYS) {
            SkillSpec spec = catalog.find(key).orElse(null);
            assertNotNull(spec, () -> key + " must be declared");
            assertTrue(spec.available(), () -> key + " must be available");
            assertNull(spec.unavailableReason(),
                    () -> key + " must not carry an unavailable reason");
            assertTrue(spec.version().matches("\\d+\\.\\d+\\.\\d+"),
                    () -> key + " must carry a semantic version");
            assertFalse(spec.plan().isEmpty(), () -> key + " must declare a retrieval plan");
            assertFalse(spec.triggers().isEmpty(), () -> key + " must be recognizable");
            assertFalse(spec.resultBlockKinds().isEmpty(),
                    () -> key + " must declare its result-block contract");
            assertFalse(spec.coverageSources().isEmpty(),
                    () -> key + " must declare the coverage it may claim");
            assertTrue(spec.citationsRequired(), () -> key + " must require citations");
            assertEquals(Set.of(Permission.AI_USE), spec.permissions(),
                    () -> key + " must gate on the assistant permission");
            assertEquals(AiSkillCatalog.Authority.READ, spec.authority(),
                    () -> key + " must not claim write authority in this increment");
            assertTrue(spec.budgets().maxModelSteps() > 0 && spec.budgets().maxModelSteps() <= 12,
                    () -> key + " must leave the model a small bounded synthesis budget");
            assertTrue(spec.budgets().maxLatencyMillis() > 0,
                    () -> key + " must declare a latency budget");
            assertTrue(spec.bounds().maxRows() > 0 && spec.bounds().maxResultBytes() > 0,
                    () -> key + " must declare row and byte bounds");
            assertTrue(spec.bounds().maxPeriodDays() <= AiChatScopeBounds.MAX_PERIOD_DAYS,
                    () -> key + " must not exceed the shared date-range bound");
            assertFalse(spec.requiredMetrics().isEmpty(),
                    () -> key + " must declare the server-computed figures it is grounded in");
            assertEquals(AiSkillCatalog.PartialBehavior.BOUNDED_PARTIAL, spec.partialBehavior(),
                    () -> key + " must be able to answer partially rather than destroy a turn");
            assertEquals(key, spec.evaluation().goldenSetId(),
                    () -> key + " must gate on its own golden set");
            assertTrue(spec.evaluation().minimumCasesPerLocale() >= 1,
                    () -> key + " must require at least one case per locale");
            assertFalse(spec.evaluation().categories().isEmpty(),
                    () -> key + " must declare which risk categories its golden set covers");
        }
    }

    @Test
    void everyPlanStepIsAllowedByTheSkillThatDeclaresIt() {
        for (SkillSpec spec : catalog.skills()) {
            for (PlanStep step : spec.plan()) {
                assertTrue(spec.allowedTools().contains(step.kind().toolName()),
                        () -> spec.key() + " runs " + step.kind().toolName()
                                + " without declaring it");
            }
        }
    }

    @Test
    void everySkillDirectiveStaysInsideThePerTurnByteBound() {
        for (SkillSpec spec : catalog.skills()) {
            int bytes = spec.directive().getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= AiSkillCatalog.maxDirectiveBytes(),
                    () -> spec.key() + " directive is " + bytes + " bytes");
            assertFalse(AiAssistantStepGuard.containsHandle(spec.directive()),
                    () -> spec.key() + " directive must not contain a record handle");
        }
    }

    @Test
    void everySkillNamesClientCopyByStableI18nKeyRatherThanBakingIt() {
        for (SkillSpec spec : catalog.skills()) {
            assertTrue(spec.nameKey().startsWith("askConnex.skills."),
                    () -> spec.key() + " must name its copy by i18n key");
            assertTrue(spec.descriptionKey().startsWith("askConnex.skills."),
                    () -> spec.key() + " must name its description by i18n key");
            assertTrue(spec.nameKey().endsWith(".name"), spec::key);
            assertTrue(spec.descriptionKey().endsWith(".description"), spec::key);
        }
        assertEquals(
                List.of("askConnex.skills.activityDigest.name",
                        "askConnex.skills.activityDigest.description"),
                List.of(catalog.find("activity_digest_v1").orElseThrow().nameKey(),
                        catalog.find("activity_digest_v1").orElseThrow().descriptionKey()));
    }

    @Test
    void anUnknownKeyIsNeitherDeclaredNorAvailable() {
        assertFalse(catalog.isKnown("relationship_brief_v2"));
        assertFalse(catalog.isAvailable("relationship_brief_v2"));
        assertFalse(catalog.isKnown(null));
        assertTrue(catalog.find("relationship_brief_v2").isEmpty());
    }
}
