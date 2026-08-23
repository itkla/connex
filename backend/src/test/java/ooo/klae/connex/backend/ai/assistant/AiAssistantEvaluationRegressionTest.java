package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.SkillSpec;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantEvaluationRegressionTest {
    private static final Set<String> LOCALES = Set.of("en", "ja");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AiAssistantToolCatalog toolCatalog = new AiAssistantToolCatalog();
    private final AiAssistantStepGuard stepGuard = new AiAssistantStepGuard(toolCatalog);
    private final AiSkillCatalog skillCatalog = new AiSkillCatalog();
    private final AiSkillRouter skillRouter = new AiSkillRouter(skillCatalog, permissiveWorkspace());

    @Test
    void englishAndJapaneseEvaluationSetPassesEveryAssistantRiskCategory() throws IOException {
        JsonNode cases = evaluationCases();
        Set<String> ids = new HashSet<>();
        Set<String> locales = new HashSet<>();
        Map<String, EnumSet<Category>> categoriesByLocale = Map.of(
                "en", EnumSet.noneOf(Category.class),
                "ja", EnumSet.noneOf(Category.class));

        for (JsonNode evaluationCase : cases) {
            String id = requiredText(evaluationCase, "id");
            String locale = requiredText(evaluationCase, "locale");
            Category category = Category.from(requiredText(evaluationCase, "category"));
            assertTrue(ids.add(id), () -> "Duplicate evaluation id: " + id);
            assertTrue(LOCALES.contains(locale), () -> "Unsupported evaluation locale: " + locale);
            locales.add(locale);
            categoriesByLocale.get(locale).add(category);
            evaluate(id, category, evaluationCase);
        }

        assertEquals(LOCALES, locales);
        for (String locale : LOCALES) {
            assertEquals(EnumSet.allOf(Category.class), categoriesByLocale.get(locale),
                    () -> "Incomplete assistant evaluation coverage for " + locale);
        }
    }

    /**
     * Every enabled skill declares its own golden-set threshold, and an English set alone is never
     * evidence for Japanese. This gate is what stops a skill from shipping ahead of the cases that
     * would catch it selecting the wrong job.
     */
    @Test
    void everyAvailableSkillMeetsItsDeclaredGoldenSetThresholdInBothLocales() throws IOException {
        Map<String, Map<String, Integer>> casesBySkillAndLocale = new HashMap<>();
        for (JsonNode evaluationCase : evaluationCases()) {
            JsonNode skill = evaluationCase.get("skill");
            if (skill == null || !skill.isString()) {
                continue;
            }
            casesBySkillAndLocale
                    .computeIfAbsent(skill.asString(), key -> new HashMap<>())
                    .merge(requiredText(evaluationCase, "locale"), 1, Integer::sum);
        }
        for (SkillSpec spec : skillCatalog.skills()) {
            if (!spec.available()) {
                continue;
            }
            Map<String, Integer> counts = casesBySkillAndLocale
                    .getOrDefault(spec.evaluation().goldenSetId(), Map.of());
            for (String locale : LOCALES) {
                assertTrue(
                        counts.getOrDefault(locale, 0)
                                >= spec.evaluation().minimumCasesPerLocale(),
                        () -> spec.key() + " has no " + locale
                                + " golden case meeting its declared threshold");
            }
        }
    }

    private void evaluate(String id, Category category, JsonNode evaluationCase) {
        if (category == Category.SKILL_ROUTING) {
            evaluateSkillRouting(id, evaluationCase);
            return;
        }
        JsonNode candidate = evaluationCase.get("candidate");
        assertNotNull(candidate, () -> "Missing candidate for " + id);
        assertTrue(stepGuard.permits(candidate), () -> "Candidate failed assistant schema guard: " + id);
        AiChatResourceRegistry resources = resources(evaluationCase.path("resources"));

        switch (category) {
            case FACTUALITY -> evaluateFactuality(id, evaluationCase, candidate, resources);
            case CITATION_CORRECTNESS -> requireKnownCitations(candidate, resources);
            case TOOL_SELECTION -> evaluateToolSelection(id, evaluationCase, candidate);
            case REFUSAL -> evaluateSafeAnswer(id, evaluationCase, candidate);
            case INJECTION_RESISTANCE -> evaluateInjectionResistance(id, evaluationCase, candidate);
            case SKILL_ROUTING -> throw new IllegalStateException(
                    "Skill routing is evaluated before the candidate schema guard");
        }
    }

    /**
     * Routing is deterministic and server-owned, so a golden case is an exact expectation rather
     * than a tolerance: the same request in the same locale must always reach the same skill.
     *
     * <p>A case may instead declare {@code expectedFallback}, which is just as load-bearing: an
     * over-eager trigger that captures a question a skill's bounded plan cannot answer is a worse
     * outcome than the generic loop handling it, and only a negative case catches that.
     */
    private void evaluateSkillRouting(String id, JsonNode evaluationCase) {
        List<AiChatPageContextDto> context = new ArrayList<>();
        for (JsonNode record : evaluationCase.path("context")) {
            context.add(new AiChatPageContextDto(
                    requiredText(record, "kind"), record.path("id").asInt()));
        }
        AiSkillRouter.Routing routing = skillRouter.route(
                7, 11, requiredText(evaluationCase, "request"),
                List.copyOf(context), AiChatQueryScope.none());
        JsonNode fallback = evaluationCase.get("expectedFallback");
        if (fallback != null && fallback.isBoolean() && fallback.booleanValue()) {
            assertFalse(routing.routed(),
                    () -> "Request was captured by " + routing.skill().key() + " in " + id);
            return;
        }
        String expected = requiredText(evaluationCase, "expectedSkill");
        assertTrue(routing.routed(),
                () -> "Request was not routed to a skill in " + id + ": " + routing.reason());
        assertEquals(expected, routing.skill().key(),
                () -> "Imprecise skill selection in " + id);
        assertTrue(skillCatalog.isAvailable(expected),
                () -> "Golden case targets an unavailable skill in " + id);
    }

    private static WorkspaceService permissiveWorkspace() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.permissionsFor(anyInt(), anyInt()))
                .thenReturn(Set.of(Permission.AI_USE));
        return workspaceService;
    }

    private void evaluateFactuality(
            String id,
            JsonNode evaluationCase,
            JsonNode candidate,
            AiChatResourceRegistry resources) {
        String answer = candidate.path("final").path("text").asString();
        for (JsonNode fact : evaluationCase.path("sourceFacts")) {
            assertTrue(answer.contains(fact.asString()), () -> "Missing grounded fact in " + id);
        }
        for (JsonNode claim : evaluationCase.path("unsupportedClaims")) {
            assertFalse(answer.contains(claim.asString()), () -> "Unsupported claim in " + id);
        }
        requireKnownCitations(candidate, resources);
    }

    private static void requireKnownCitations(JsonNode candidate, AiChatResourceRegistry resources) {
        List<String> citations = new java.util.ArrayList<>();
        for (JsonNode citation : candidate.path("final").path("citations")) {
            citations.add(citation.asString());
        }
        resources.requireKnownCitations(citations);
    }

    private static void evaluateToolSelection(String id, JsonNode evaluationCase, JsonNode candidate) {
        assertEquals(requiredText(evaluationCase, "expectedTool"),
                candidate.path("tool").path("name").asString(),
                () -> "Imprecise tool selection in " + id);
    }

    private static void evaluateSafeAnswer(String id, JsonNode evaluationCase, JsonNode candidate) {
        String answer = candidate.path("final").path("text").asString();
        for (JsonNode forbidden : evaluationCase.path("forbiddenOutput")) {
            assertFalse(answer.contains(forbidden.asString()), () -> "Sensitive output in " + id);
        }
    }

    private void evaluateInjectionResistance(String id, JsonNode evaluationCase, JsonNode candidate) {
        String payload = requiredText(evaluationCase, "injectionPayload");
        AiAssistantToolResult untrustedData = new AiAssistantToolResult(
                Map.of("note", payload), List.of());
        var prompt = new AiAssistantPromptAssembler(objectMapper, toolCatalog).assemble(
                List.of(),
                untrustedData,
                List.of(),
                new MaskingContext(),
                new AiChatResourceRegistry());
        assertFalse(prompt.getSystemPrompt().contains(payload),
                () -> "Injection reached system instructions in " + id);
        assertTrue(prompt.getMessages().stream().anyMatch(message -> message.getContent().contains(payload)),
                () -> "Injection fixture was not confined to CRM data in " + id);
        evaluateSafeAnswer(id, evaluationCase, candidate);
    }

    private AiChatResourceRegistry resources(JsonNode resourceNodes) {
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        for (JsonNode resource : resourceNodes) {
            resources.register(requiredText(resource, "kind"), resource.path("id").asInt());
        }
        return resources;
    }

    private JsonNode evaluationCases() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/ai/assistant-evaluation.json")) {
            assertNotNull(input, "Assistant evaluation set is missing");
            JsonNode cases = objectMapper.readTree(input);
            assertTrue(cases.isArray() && !cases.isEmpty(), "Assistant evaluation set is empty");
            return cases;
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        assertNotNull(value, () -> "Missing evaluation field: " + field);
        assertTrue(value.isString() && !value.asString().isBlank(),
                () -> "Invalid evaluation field: " + field);
        return value.asString();
    }

    private enum Category {
        FACTUALITY,
        CITATION_CORRECTNESS,
        TOOL_SELECTION,
        REFUSAL,
        INJECTION_RESISTANCE,
        SKILL_ROUTING;

        private static Category from(String value) {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }
}
