package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantEvaluationRegressionTest {
    private static final Set<String> LOCALES = Set.of("en", "ja");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AiAssistantToolCatalog toolCatalog = new AiAssistantToolCatalog();
    private final AiAssistantStepGuard stepGuard = new AiAssistantStepGuard(toolCatalog);

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

    private void evaluate(String id, Category category, JsonNode evaluationCase) {
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
        }
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
        INJECTION_RESISTANCE;

        private static Category from(String value) {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }
}
