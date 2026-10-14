package ooo.klae.connex.backend.ai.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.framework;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiMediaAdmissionService;
import ooo.klae.connex.backend.ai.AiOrganizationBudgetCoordinator;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderRouter;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.ReferenceService;
import ooo.klae.connex.backend.services.SavedViewService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.SegmentService;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;

/** Real masking, scope-read, prompt-assembly and invocation fixtures with mocked boundaries. */
final class AiAssistantMaskingTestSupport {
    private final int workspaceId;
    private final int organizationId;

    AiAssistantMaskingTestSupport(int workspaceId, int organizationId) {
        this.workspaceId = workspaceId;
        this.organizationId = organizationId;
    }

    private final List<Object> fixtureMocks = new ArrayList<>();
    private Optional<ProviderHarness> providerHarness = Optional.empty();
    private Optional<ScopeHarness> scopeHarness = Optional.empty();

    /** Releases request captures retained by Mockito's inline mock registry after each test. */
    void releaseFixtureMocks() {
        fixtureMocks.forEach(value -> framework().clearInlineMock(value));
        fixtureMocks.clear();
    }

    <T> T fixtureMock(Class<T> type) {
        T value = mock(type);
        fixtureMocks.add(value);
        return value;
    }

    AiAssistantToolResult scopeResult(
            Person person, String note, AiChatResourceRegistry resources, ObjectMapper mapper) {
        if (scopeHarness.isEmpty()) {
            scopeHarness = Optional.of(newScopeHarness(mapper));
        }
        ScopeHarness harness = scopeHarness.orElseThrow();
        reset(harness.people(), harness.activities());
        when(harness.people().getAssistantProcessablePersonIds(workspaceId)).thenReturn(List.of(person.getId()));
        when(harness.people().getByIds(eq(workspaceId), anyList())).thenReturn(List.of(person));
        when(harness.activities().countAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(), anyBoolean())).thenReturn(1L);
        when(harness.activities().getAiAssistantScopeActivities(
                anyInt(), anyList(), anyString(), anyList(), any(), any(), anyList(),
                anyBoolean(), anyInt(), anyInt())).thenReturn(List.of(
                        new AiAssistantScopeActivity(1, person.getId(), "meeting", "Follow up", note,
                                "2026-08-22 10:00:00", person.getId(), null)));
        try {
            return harness.service().scopeActivities(
                    AiChatQueryScope.none(), "person", null, List.of(), 30, 50, 5, resources);
        } finally {
            clearInvocations(harness.mocks().toArray());
        }
    }

    private ScopeHarness newScopeHarness(ObjectMapper mapper) {
        int firstMock = fixtureMocks.size();
        ActivityMapper activities = fixtureMock(ActivityMapper.class);
        PersonMapper people = fixtureMock(PersonMapper.class);
        WorkspaceService workspaceService = fixtureMock(WorkspaceService.class);
        OrganizationWorkspaceScopeControlAccess scope = fixtureMock(OrganizationWorkspaceScopeControlAccess.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(scope.getForWorkspace(workspaceId)).thenReturn(new WorkspaceScope(
                organizationId, List.of(workspaceId), "[" + workspaceId + "]"));
        ReferenceService references = fixtureMock(ReferenceService.class);
        when(references.hydrateActivities(anyInt(), anyList())).thenAnswer(call -> call.getArgument(1));
        AiAssistantScopeReadService service = new AiAssistantScopeReadService(
                activities, fixtureMock(SegmentService.class), fixtureMock(SegmentMapper.class), fixtureMock(SavedViewService.class),
                fixtureMock(ScoringService.class), fixtureMock(DealRiskService.class), people, fixtureMock(CompanyMapper.class), fixtureMock(DealMapper.class),
                workspaceService, scope, mapper, Clock.systemUTC(), references);
        return new ScopeHarness(service, people, activities,
                List.copyOf(fixtureMocks.subList(firstMock, fixtureMocks.size())));
    }

    private record ScopeHarness(AiAssistantScopeReadService service, PersonMapper people,
            ActivityMapper activities, List<Object> mocks) {
    }

    AiInvocation invocation(String text, MaskingContext context, ObjectMapper mapper) {
        AiChatMessage message = new AiChatMessage();
        message.setAuthorKind("user");
        message.setContent(text);
        return new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(message), new AiAssistantToolResult(Map.of(), List.of()), List.of(),
                        context, new AiChatResourceRegistry(),
                        AiAssistantToolCatalog.ALL), 256, 0.1);
    }

    ProviderHarness providerHarness(ObjectMapper mapper) {
        if (providerHarness.isEmpty()) {
            providerHarness = Optional.of(newProviderHarness(mapper));
        }
        return providerHarness.orElseThrow();
    }

    AiCompletionRequest firstProviderRequest(AiInvocation invocation, ObjectMapper mapper) {
        ProviderHarness harness = providerHarness(mapper);
        try {
            harness.service().complete(invocation);
            ArgumentCaptor<AiCompletionRequest> request = ArgumentCaptor.forClass(AiCompletionRequest.class);
            verify(harness.provider()).complete(request.capture());
            return request.getValue();
        } finally {
            clearInvocations(harness.mocks().toArray());
        }
    }

    void allowUnmaskedDisclosure(ObjectMapper mapper) {
        var config = providerHarness(mapper).mocks().stream()
                .filter(AiProviderConfigService.class::isInstance)
                .map(AiProviderConfigService.class::cast).findFirst().orElseThrow();
        when(config.resolveForOrg(organizationId, 11)).thenReturn(new ResolvedAiProvider(
                "deterministic", null, "deterministic-model", "https://provider.example.test/v1",
                null, null, null, false, true, ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED,
                AiCredentials.of(Map.of())));
    }

    AssistantCapture captureAssistantPayload(
            ooo.klae.connex.backend.ai.masking.MaskedPrompt prompt, MaskingContext context,
            ObjectMapper mapper, boolean nativeProtocol,
            List<ooo.klae.connex.backend.ai.provider.AiToolExchange> exchanges,
            String output, boolean assignment, AiChatStreamingProgress.Observer observer) {
        ProviderHarness harness = providerHarness(mapper);
        AiProvider provider = harness.provider();
        when(provider.toolCallingCapability(any())).thenReturn(
                ooo.klae.connex.backend.ai.provider.AiToolCallingMode.NATIVE_FUNCTIONS);
        when(provider.supportsStreaming(any())).thenReturn(observer != null);
        java.util.function.Function<AiCompletionRequest, AiCompletionResult> completion = request -> {
            request.providerAttemptExecutor().execute(() -> {
                request.providerAttemptExecutor().beforeSend();
                if (observer != null && !assignment) {
                    observer.onContentDelta(output);
                }
                return "";
            });
            return new AiCompletionResult(output, 1, 1, assignment ? "tool_calls" : "stop",
                    ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement.PROMPT_ONLY,
                    "", ooo.klae.connex.backend.ai.provider.AiReasoningMode.NONE,
                    nativeProtocol && assignment ? List.of(new ooo.klae.connex.backend.ai.provider.AiToolCall(
                            "member-guess", "assign_owner", "{\"handle\":\"r1\",\"owner\":\"{{M1}}\"}")) : List.of());
        };
        org.mockito.Mockito.doAnswer(call -> completion.apply(call.getArgument(0))).when(provider).complete(any());
        org.mockito.Mockito.doAnswer(call -> completion.apply(call.getArgument(0))).when(provider).completeStreaming(any(), any());
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, context, prompt,
                List.of(), 256, 0.1, false, null,
                nativeProtocol ? ooo.klae.connex.backend.ai.provider.AiInvocationProtocol.NATIVE_TOOLS
                        : ooo.klae.connex.backend.ai.provider.AiInvocationProtocol.JSON_REACT,
                null, false, observer);
        var catalog = new AiAssistantToolCatalog();
        var assembler = new AiAssistantPromptAssembler(mapper, catalog);
        var schema = new AiAssistantStepSchema(mapper, catalog);
        var admission = fixtureMock(AiInvocationAdmissionService.DirectAdmission.class);
        int warnings;
        if (nativeProtocol) {
            var outcome = harness.service().completeNativeToolsRepairable(invocation,
                    tools.jackson.databind.JsonNode.class,
                    ooo.klae.connex.backend.ai.AiRawOutputGuard.PERMIT_ALL,
                    ooo.klae.connex.backend.ai.AiRawOutputGuard.PERMIT_ALL,
                    schema.finalResponseSchema(),
                    new ooo.klae.connex.backend.ai.provider.AiNativeToolRequest(
                            assembler.nativeToolDefinitions(AiAssistantToolCatalog.ALL), exchanges),
                    admission, () -> { });
            warnings = switch (outcome) {
                case ooo.klae.connex.backend.ai.AiNativeToolCompletion.Tool<?> tool -> tool.demaskWarnings();
                case ooo.klae.connex.backend.ai.AiNativeToolCompletion.Content<?> content -> parsedWarnings(content.attempt().outcome());
                case ooo.klae.connex.backend.ai.AiNativeToolCompletion.Malformed<?> malformed -> malformed.demaskWarnings();
            };
        } else {
            warnings = parsedWarnings(harness.service().completeStructuredRepairable(invocation,
                    tools.jackson.databind.JsonNode.class,
                    ooo.klae.connex.backend.ai.AiRawOutputGuard.PERMIT_ALL,
                    schema.responseSchema(AiAssistantToolCatalog.ALL), admission, () -> { }).outcome());
        }
        ArgumentCaptor<AiCompletionRequest> request = ArgumentCaptor.forClass(AiCompletionRequest.class);
        if (observer == null) {
            verify(provider).complete(request.capture());
        } else {
            verify(provider).completeStreaming(request.capture(), any());
        }
        clearInvocations(harness.mocks().toArray());
        return new AssistantCapture(request.getValue(), warnings);
    }

    private static int parsedWarnings(ooo.klae.connex.backend.ai.AiStructuredOutcome<?> outcome) {
        return org.junit.jupiter.api.Assertions.assertInstanceOf(
                ooo.klae.connex.backend.ai.AiStructuredOutcome.Parsed.class, outcome).demaskWarnings();
    }

    record AssistantCapture(AiCompletionRequest request, int warnings) {
    }

    private ProviderHarness newProviderHarness(ObjectMapper mapper) {
        int firstMock = fixtureMocks.size();
        AiProvider provider = fixtureMock(AiProvider.class);
        when(provider.providerId()).thenReturn("deterministic");
        when(provider.maxOutputTokens(any())).thenReturn(4_096);
        when(provider.contextWindowTokens(any())).thenReturn(128_000);
        when(provider.complete(any())).thenAnswer(call -> {
            AiCompletionRequest request = call.getArgument(0);
            String output = request.providerAttemptExecutor().execute(() -> {
                request.providerAttemptExecutor().beforeSend();
                return "Ready";
            });
            return new AiCompletionResult(output, 1, 1, "stop");
        });
        WorkspaceService workspaceService = fixtureMock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(workspaceService.getCurrentOrgId()).thenReturn(organizationId);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        AiProviderConfigService configService = fixtureMock(AiProviderConfigService.class);
        when(configService.resolveForOrg(organizationId, 11)).thenReturn(new ResolvedAiProvider(
                "deterministic", null, "deterministic-model", "https://provider.example.test/v1",
                null, null, null, false, true, AiCredentials.of(Map.of())));
        AiOrganizationBudgetCoordinator budget = fixtureMock(AiOrganizationBudgetCoordinator.class);
        AiOrganizationBudgetCoordinator.Lease budgetLease = fixtureMock(AiOrganizationBudgetCoordinator.Lease.class);
        when(budgetLease.deadline()).thenAnswer(call -> AiRequestDeadline.afterMillis(60_000));
        when(budget.reserve(eq(organizationId), any(AiInvocation.class), anyString()))
                .thenReturn(budgetLease);
        AiFeatureGate gate = fixtureMock(AiFeatureGate.class);
        when(gate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(true);
        AiInvocationService invocationService = new AiInvocationService(
                gate, fixtureMock(AiInvocationAdmissionService.class), fixtureMock(AiMediaAdmissionService.class),
                configService, new AiProviderRouter(List.of(provider)), new AiRestrictionEpoch(),
                workspaceService, fixtureMock(AuditService.class), mapper, budget, Clock.systemUTC());

        return new ProviderHarness(invocationService, provider,
                List.copyOf(fixtureMocks.subList(firstMock, fixtureMocks.size())));
    }

    record ProviderHarness(AiInvocationService service, AiProvider provider, List<Object> mocks) {
    }

}
