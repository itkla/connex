package ooo.klae.connex.backend.ai.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

import ooo.klae.connex.backend.ai.provider.openai.OpenAiCompatibleAdapter;

/**
 * The per-step call ceiling's SPI default, its fail-closed reading, and the capability record's
 * own range check.
 *
 * <p>Every adapter is single-call unless it explicitly says otherwise, and only two may: the
 * OpenAI-compatible adapter, which answers from an operator declaration, and the scripted
 * provider, which rehearses a declared endpoint. Azure, Bedrock and Vertex declare no native tools
 * at all, so an override there would be an enablement nobody probed.
 */
class AiProviderParallelToolCallsTest {

    private static final String SCRIPTED_PACKAGE = "ooo.klae.connex.backend.ai.provider.scripted";

    private static final AiProviderTarget TARGET = new AiProviderTarget(
            "azure_openai", null, "gpt-5", "https://example.test", null, null, null, false);

    /** The SPI answers one call per step for any adapter that does not override it. */
    @Test
    void theSpiDefaultIsOneCallPerStep() {
        AiProvider adapter = mock(AiProvider.class, CALLS_REAL_METHODS);

        assertEquals(1, adapter.parallelToolCallLimit(TARGET));
    }

    /**
     * Every production adapter except the two that answer from a declaration inherits that default.
     *
     * <p>Enumerated from the classpath rather than listed, so a new adapter that overrides the
     * ceiling fails here until someone decides it belongs beside the declaring two.
     */
    @Test
    void onlyTheDeclaringAdaptersOverrideTheCallCeiling() throws Exception {
        Set<String> inheriting = new TreeSet<>();
        for (Class<?> adapter : productionAdapters()) {
            Class<?> declaring = adapter
                    .getMethod("parallelToolCallLimit", AiProviderTarget.class)
                    .getDeclaringClass();
            if (adapter == OpenAiCompatibleAdapter.class
                    || adapter.getPackageName().equals(SCRIPTED_PACKAGE)) {
                assertEquals(adapter, declaring, adapter.getName());
            } else {
                assertEquals(AiProvider.class, declaring, adapter.getName()
                        + " must inherit the single-call default");
                inheriting.add(adapter.getSimpleName());
            }
        }
        assertTrue(
                inheriting.containsAll(List.of(
                        "AzureOpenAiAdapter", "BedrockAnthropicAdapter", "VertexAdapter")),
                "the scan must see every non-declaring adapter, found " + inheriting);
    }

    /** Anything outside 1..ceiling reads as one call, never as the ceiling. */
    @Test
    void anOutOfRangeAnswerReadsAsOneCall() {
        int max = AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS;
        for (int outOfRange : List.of(Integer.MIN_VALUE, -1, 0, max + 1, Integer.MAX_VALUE)) {
            assertEquals(1, AiProviderCapabilities.parallelToolCallsOrSingle(outOfRange),
                    "answer " + outOfRange);
        }
        for (int inRange = 1; inRange <= max; inRange++) {
            assertEquals(inRange, AiProviderCapabilities.parallelToolCallsOrSingle(inRange));
        }
    }

    /** The record refuses a ceiling outside 1..{@code MAX_PARALLEL_TOOL_CALLS} outright. */
    @Test
    void capabilitiesRefuseACallCeilingOutsideTheRange() {
        int max = AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS;
        for (int outOfRange : List.of(0, max + 1)) {
            assertThrows(IllegalArgumentException.class, () -> capabilities(outOfRange),
                    "ceiling " + outOfRange);
        }
        assertDoesNotThrow(() -> capabilities(1));
        assertDoesNotThrow(() -> capabilities(max));
    }

    private static AiProviderCapabilities capabilities(int parallelToolCalls) {
        return new AiProviderCapabilities(
                AiStructuredOutputEnforcement.JSON_SCHEMA,
                AiReasoningMode.NONE,
                200_000,
                16_384,
                AiToolCallingMode.NATIVE_FUNCTIONS,
                AiReasoningMode.NONE,
                false,
                parallelToolCalls);
    }

    private static Set<Class<?>> productionAdapters() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AiProvider.class));
        Set<Class<?>> adapters = new HashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("ooo.klae.connex.backend")) {
            Class<?> type = ClassUtils.forName(
                    candidate.getBeanClassName(),
                    AiProviderParallelToolCallsTest.class.getClassLoader());
            if (type.getProtectionDomain().getCodeSource().getLocation().getPath()
                    .contains("/main/")) {
                adapters.add(type);
            }
        }
        return adapters;
    }
}
