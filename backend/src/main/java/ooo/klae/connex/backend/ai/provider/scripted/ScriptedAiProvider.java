package ooo.klae.connex.backend.ai.provider.scripted;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderCallerDeadlineExceededException;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderIdleTimeoutException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;

/**
 * Fixture-driven provider that shadows the real OpenAI-compatible adapter under the
 * {@code ai-scripted-provider} profile.
 *
 * <p>It answers under the {@code openai_compatible} provider id on purpose. The provider id is a
 * closed set in two production controls — a database {@code CHECK} constraint and
 * {@code AiProviderConfigService}'s supported-provider sets — and a test seam is not a reason to
 * weaken either. {@code AiProviderRouter} refuses duplicate ids, which is why the real adapter
 * carries the matching profile negation.
 *
 * <p><b>Dispatch accounting is load-bearing.</b> Every path, including a declared failure, runs
 * inside {@code providerAttemptExecutor().execute(...)} or {@code .executeStream(...)} — the seam
 * that runs the restriction epoch, the feature gate, the provider guard and the admission
 * commitment — and calls {@code beforeSend()} inside the deferred supplier at the point the real
 * adapter would write bytes. {@code beforeSend()} is the only route to the budget lease's
 * dispatched mark; {@code execute} never touches the lease. A declared failure still dispatches
 * because a transport failure in production happens after the bytes leave.
 *
 * <p><b>The call order mirrors the real transport, not a convenient one.</b> On the streamed path
 * {@code OpenAiCompatibleClient.sendStream} opens the transport, re-checks cancellation and the
 * caller deadline, and only then calls {@code beforeSend}; opening the transport is a gate, because
 * {@code AiChatStreamingProgress.Observer.onTransportOpen} refuses a turn that is no longer
 * running. Marking the lease dispatched before that gate would record a send production would
 * never record. The buffered path re-checks the same way before its own {@code beforeSend}.
 */
public class ScriptedAiProvider implements AiProvider {

    private static final String PROVIDER_OPENAI_COMPATIBLE = "openai_compatible";
    private static final String STOP_REASON_STOP = "stop";
    private static final String STOP_REASON_TOOL_CALLS = "tool_calls";
    private static final int SCRIPTED_TOKENS = 1;

    private final ScriptedAiScriptLoader scripts;
    private final ScriptedAiRequestJournal journal;
    private final List<ScriptedAiStepInterceptor> interceptors;
    private final AiProperties aiProperties;

    /**
     * Creates the scripted provider.
     * @param scripts loaded and validated scripts
     * @param journal bounded record of received requests
     * @param interceptors loop-thread hooks; empty on the main classpath
     * @param aiProperties bound AI configuration, read for the provider request timeout
     */
    public ScriptedAiProvider(
            ScriptedAiScriptLoader scripts,
            ScriptedAiRequestJournal journal,
            List<ScriptedAiStepInterceptor> interceptors,
            AiProperties aiProperties) {
        this.scripts = Objects.requireNonNull(scripts, "scripts");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.interceptors = List.copyOf(Objects.requireNonNull(interceptors, "interceptors"));
        this.aiProperties = Objects.requireNonNull(aiProperties, "aiProperties");
    }

    @Override
    public String providerId() {
        return PROVIDER_OPENAI_COMPATIBLE;
    }

    @Override
    public AiStructuredOutputEnforcement structuredOutputCapability(AiProviderTarget target) {
        return capabilityClass(target).structuredOutput();
    }

    @Override
    public AiReasoningMode reasoningCapability(AiProviderTarget target) {
        return capabilityClass(target).reasoning();
    }

    @Override
    public AiReasoningMode nativeToolReasoningCapability(AiProviderTarget target) {
        return capabilityClass(target).reasoning();
    }

    @Override
    public int contextWindowTokens(AiProviderTarget target) {
        return capabilityClass(target).contextWindowTokens();
    }

    @Override
    public int maxOutputTokens(AiProviderTarget target) {
        return capabilityClass(target).maxOutputTokens();
    }

    @Override
    public AiToolCallingMode toolCallingCapability(AiProviderTarget target) {
        return capabilityClass(target).toolCalling();
    }

    @Override
    public boolean supportsStreaming(AiProviderTarget target) {
        return capabilityClass(target).streaming();
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        Resolution resolution = resolve(request);
        AiRequestDeadline deadline = request.providerAttemptExecutor()
                .deadline(aiProperties.getRequestTimeoutMs());
        AtomicReference<AiCompletionResult> result = new AtomicReference<>();
        request.providerAttemptExecutor().execute(() -> {
            requireAttemptLive(deadline, null);
            request.providerAttemptExecutor().beforeSend();
            resolution.entry().markDispatched();
            result.set(emit(request, resolution));
            return "";
        });
        return Objects.requireNonNull(result.get(), "scripted completion");
    }

    @Override
    public AiCompletionResult completeStreaming(
            AiCompletionRequest request,
            AiProviderStreamObserver observer) {
        if (observer == null) {
            throw new AiProviderException("AI streaming completion request is required");
        }
        Resolution resolution = resolve(request);
        if (!resolution.capabilityClass().streaming()) {
            throw new AiProviderException("AI provider does not support streaming");
        }
        AiRequestDeadline deadline = request.providerAttemptExecutor()
                .deadline(aiProperties.getRequestTimeoutMs());
        observer.onReasoningMode(request.reasoningMode());
        AtomicBoolean cancelled = new AtomicBoolean();
        return request.providerAttemptExecutor().executeStream(() -> {
            observer.onTransportOpen(() -> cancelled.set(true));
            try {
                requireAttemptLive(deadline, cancelled);
                request.providerAttemptExecutor().beforeSend();
                resolution.entry().markDispatched();
                AiCompletionResult result = emit(request, resolution);
                for (String delta : streamedDeltas(resolution.step(), result.text())) {
                    requireAttemptLive(deadline, cancelled);
                    observer.onContentDelta(delta);
                    observer.onNetworkChunk();
                }
                return result;
            } finally {
                observer.onTransportClosed();
            }
        });
    }

    /**
     * Refuses an attempt whose caller deadline has elapsed, or that has been cancelled.
     *
     * <p>The real client makes exactly this check immediately before {@code beforeSend} on both
     * paths, and aborts a live stream by cancelling the HTTP exchange, which surfaces as the same
     * refusal. Without it a scripted turn would answer for a member who already cancelled and
     * would mark a budget lease dispatched for a send production would have abandoned.
     *
     * @param deadline the attempt deadline the executor handed out
     * @param cancellation cancellation flag the transport registered, or {@code null} when buffered
     */
    private static void requireAttemptLive(AiRequestDeadline deadline, AtomicBoolean cancellation) {
        if ((cancellation != null && cancellation.get())
                || deadline.isExpired()
                || Thread.currentThread().isInterrupted()) {
            throw new AiProviderCallerDeadlineExceededException();
        }
    }

    private Resolution resolve(AiCompletionRequest request) {
        if (request == null) {
            throw new AiProviderException("AI completion request is required");
        }
        AiProviderTarget target = request.target();
        if (!PROVIDER_OPENAI_COMPATIBLE.equals(target.provider())) {
            throw new AiProviderException("Unsupported AI provider");
        }
        ScriptedAiCapabilityClass capabilityClass = capabilityClass(target);
        ScriptedAiRequestJournal.Entry entry = journal.record(request);
        ScriptedAiTurnCursor cursor = ScriptedAiTurnCursor.of(request, scripts.selectors());
        ScriptedAiScript script = scripts.bySelector(cursor.selector());
        if (script == null) {
            throw new AiProviderException("Scripted AI request matched no script selector");
        }
        if (script.capabilityClass() != capabilityClass) {
            throw new AiProviderException(
                    "Scripted AI script was authored for another capability class");
        }
        ScriptedAiStep step = script.resolve(cursor).orElseThrow(() ->
                new AiProviderException("Scripted AI script has no step for this request"));
        for (ScriptedAiStepInterceptor interceptor : interceptors) {
            interceptor.beforeEmit(script.id(), cursor.completedToolCalls());
        }
        return new Resolution(script, step, cursor, capabilityClass, entry);
    }

    private static AiCompletionResult emit(AiCompletionRequest request, Resolution resolution) {
        ScriptedAiStep.Emission emission = resolution.step().emit();
        AiStructuredOutputEnforcement enforcement = enforcement(request, resolution);
        return switch (emission.kind()) {
            case FAILURE -> throw failure(emission.failureKind());
            case TOOL_CALL -> toolCall(request, resolution, emission, enforcement);
            case FINAL, MALFORMED -> new AiCompletionResult(
                    emission.text(),
                    SCRIPTED_TOKENS,
                    SCRIPTED_TOKENS,
                    STOP_REASON_STOP,
                    enforcement,
                    emission.reasoning(),
                    request.reasoningMode(),
                    List.of());
        };
    }

    private static AiCompletionResult toolCall(
            AiCompletionRequest request,
            Resolution resolution,
            ScriptedAiStep.Emission emission,
            AiStructuredOutputEnforcement enforcement) {
        if (!resolution.cursor().nativeProtocol()) {
            return new AiCompletionResult(
                    jsonProtocolToolStep(emission),
                    SCRIPTED_TOKENS,
                    SCRIPTED_TOKENS,
                    STOP_REASON_STOP,
                    enforcement,
                    emission.reasoning(),
                    request.reasoningMode(),
                    List.of());
        }
        return new AiCompletionResult(
                "",
                SCRIPTED_TOKENS,
                SCRIPTED_TOKENS,
                STOP_REASON_TOOL_CALLS,
                enforcement,
                emission.reasoning(),
                request.reasoningMode(),
                List.of(new AiToolCall(
                        callId(resolution),
                        emission.toolName(),
                        emission.arguments())));
    }

    /**
     * The JSON-protocol equivalent of one native function call.
     *
     * <p>The arguments are spliced in as raw JSON rather than re-serialized, because the loader has
     * already refused a fixture whose arguments are not JSON text and re-encoding would change
     * what a golden authored.
     */
    private static String jsonProtocolToolStep(ScriptedAiStep.Emission emission) {
        return "{\"tool\":{\"name\":\"" + emission.toolName() + "\",\"args\":"
                + emission.arguments() + "},\"final\":null}";
    }

    private static String callId(Resolution resolution) {
        return "scripted_" + resolution.script().id() + "_"
                + resolution.step().afterToolCalls();
    }

    /**
     * The structured enforcement the scripted provider reports as applied.
     *
     * <p>Derived from the request exactly as the real OpenAI-compatible adapter derives it, then
     * bounded by what the capability class declares, so a prompt-only class never claims a
     * provider-native schema it does not enforce.
     */
    private static AiStructuredOutputEnforcement enforcement(
            AiCompletionRequest request, Resolution resolution) {
        if (resolution.capabilityClass().structuredOutput()
                == AiStructuredOutputEnforcement.PROMPT_ONLY) {
            return AiStructuredOutputEnforcement.PROMPT_ONLY;
        }
        if (request.reasoningMode() == AiReasoningMode.TAGGED
                || request.outputMode() != AiOutputMode.JSON) {
            return AiStructuredOutputEnforcement.PROMPT_ONLY;
        }
        return request.responseSchema() == null
                ? AiStructuredOutputEnforcement.JSON_OBJECT
                : AiStructuredOutputEnforcement.JSON_SCHEMA;
    }

    private static List<String> streamedDeltas(ScriptedAiStep step, String text) {
        if (!step.emit().deltas().isEmpty()) {
            return step.emit().deltas();
        }
        return text.isEmpty() ? List.of() : List.of(text);
    }

    private static AiProviderException failure(ScriptedAiStep.FailureKind failureKind) {
        return switch (failureKind) {
            case TRANSPORT -> new AiProviderException("Scripted AI provider transport failed");
            case REJECTED -> new AiProviderRequestRejectedException("Scripted AI provider", 400);
            case IDLE_TIMEOUT -> new AiProviderIdleTimeoutException(
                    "Scripted AI provider stream went idle");
            case DEADLINE -> new AiProviderCallerDeadlineExceededException();
        };
    }

    private static ScriptedAiCapabilityClass capabilityClass(AiProviderTarget target) {
        return ScriptedAiCapabilityClass.forModelId(target.modelId());
    }

    private record Resolution(
            ScriptedAiScript script,
            ScriptedAiStep step,
            ScriptedAiTurnCursor cursor,
            ScriptedAiCapabilityClass capabilityClass,
            ScriptedAiRequestJournal.Entry entry) {
    }
}
