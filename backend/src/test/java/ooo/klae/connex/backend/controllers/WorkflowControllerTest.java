package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.config.SecurityConfig;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDraftRequest;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowLegacyRuleResolutionDto;
import ooo.klae.connex.backend.dto.WorkflowListItemDto;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.dto.WorkflowRunDetailDto;
import ooo.klae.connex.backend.dto.WorkflowRunPageDto;
import ooo.klae.connex.backend.dto.WorkflowRunSummaryDto;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto;
import ooo.klae.connex.backend.dto.WorkflowValidationDto;
import ooo.klae.connex.backend.dto.WorkflowVersionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkflowRunReadService;
import ooo.klae.connex.backend.services.WorkflowRuntimeOwnershipService;
import ooo.klae.connex.backend.services.WorkflowService;
import ooo.klae.connex.backend.services.WorkflowSimulationService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.sso.CompositeClientRegistrationRepository;
import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;

@WebMvcTest(
    controllers = {WorkflowController.class, WorkflowRunController.class},
    properties = {
        "connex.security.csrf-enabled=true",
        "connex.sso.enabled=false",
        "connex.request-limits.workflow-max-body-bytes=512"
    }
)
@Import({
    SecurityConfig.class,
    RequestBodySizeProperties.class,
    WorkflowControllerTest.MapperTestConfig.class
})
class WorkflowControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private WorkflowService workflowService;
    @MockitoBean private WorkflowRunReadService workflowRunReadService;
    @MockitoBean private WorkflowRuntimeOwnershipService runtimeOwnershipService;
    @MockitoBean private WorkflowSimulationService simulationService;
    @MockitoBean private WorkspaceService workspaceService;
    @MockitoBean private CompositeClientRegistrationRepository clientRegistrationRepository;
    @MockitoBean private SocialLoginClientRegistrations socialLoginClientRegistrations;
    @MockitoBean private DbRelyingPartyRegistrationRepository relyingPartyRegistrationRepository;
    @MockitoBean private SsoAuthenticationSuccessHandler ssoAuthenticationSuccessHandler;
    @MockitoBean private SessionSecurityService sessionSecurityService;
    @MockitoBean private BusinessCardRateLimiter businessCardRateLimiter;
    @MockitoBean private CapabilityEntitlement capabilityEntitlement;
    @MockitoBean private TenantCatalogResolver tenantCatalogResolver;
    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private WorkspaceCookie workspaceCookie;
    @MockitoBean private ErrorReporter errorReporter;
    @MockitoBean private WorkspaceRequestResolver workspaceRequestResolver;

    @Test
    @WithMockUser
    void exactLifecycleRoutesReturnTheirContractStatusesAndBodies() throws Exception {
        WorkflowDto workflow = workflow(false);
        WorkflowDto enabled = workflow(true);
        WorkflowVersionDto version = version();
        when(workflowService.list(false)).thenReturn(List.of(listItem()));
        when(workflowService.create(any(WorkflowCreateRequest.class))).thenReturn(workflow);
        when(workflowService.getById(42)).thenReturn(workflow);
        when(workflowService.saveDraft(any(Integer.class), any(WorkflowDraftRequest.class)))
            .thenReturn(workflow);
        when(workflowService.validate(42)).thenReturn(new WorkflowValidationDto(
            3, true, true, true, List.of(), List.of(), List.of()));
        when(simulationService.simulate(eq(42), any())).thenReturn(
            new WorkflowSimulationDto(
                WorkflowSimulationDto.Result.WOULD_COMPLETE,
                List.of(),
                List.of()));
        when(workflowService.resolveLegacyRule(77))
            .thenReturn(new WorkflowLegacyRuleResolutionDto(42));
        when(workflowService.publish(any(Integer.class), any(WorkflowPublishRequest.class)))
            .thenReturn(workflow);
        when(workflowService.enable(42)).thenReturn(enabled);
        when(workflowService.disable(42)).thenReturn(workflow);
        when(workflowService.archive(42)).thenReturn(workflow);
        when(workflowService.restore(42)).thenReturn(workflow);
        when(runtimeOwnershipService.cutOverToCanonical(42, 88L)).thenReturn(workflow);
        when(runtimeOwnershipService.rollBackToLegacy(42, 88L)).thenReturn(workflow);
        when(workflowService.versions(42)).thenReturn(List.of(version));
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 2, 12, 0);
        WorkflowRunSummaryDto.Trigger runTrigger = new WorkflowRunSummaryDto.Trigger(
            "entity_change", "company.updated", "company", 91);
        WorkflowRunSummaryDto run = new WorkflowRunSummaryDto(
            "canonical-301", "canonical", "succeeded", null, null, runTrigger,
            startedAt, startedAt.plusSeconds(1), 1_000L, null, true);
        when(workflowRunReadService.listRuns(42, 10, "frozen"))
            .thenReturn(new WorkflowRunPageDto(List.of(run), "next"));
        when(workflowRunReadService.getRun(42, "canonical-301"))
            .thenReturn(new WorkflowRunDetailDto(
                "canonical-301", "canonical", 42, "succeeded", null, null, null,
                runTrigger, startedAt, startedAt.plusSeconds(1), 1_000L, null, true,
                List.of()));

        mockMvc.perform(get("/api/workflows"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(42));
        mockMvc.perform(post("/api/workflows")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/workflows/42"))
            .andExpect(jsonPath("$.definition.schemaVersion").value(1))
            .andExpect(jsonPath("$.canvas.viewport.zoom").value(1));
        mockMvc.perform(get("/api/workflows/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42));
        mockMvc.perform(put("/api/workflows/42/draft")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/workflows/42/validate").with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.draftRevision").value(3))
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.canPublish").value(true))
            .andExpect(jsonPath("$.errors").isEmpty());
        mockMvc.perform(post("/api/workflows/42/simulate")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":3,\"recordId\":91}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("would_complete"));
        mockMvc.perform(get("/api/workflows/legacy-rules/77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workflowId").value(42));
        mockMvc.perform(post("/api/workflows/42/publish")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":3}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/workflows/42/enable").with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));
        mockMvc.perform(post("/api/workflows/42/disable").with(csrf().asHeader()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(post("/api/workflows/42/archive").with(csrf().asHeader()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/workflows/42/restore").with(csrf().asHeader()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/workflows/42/runtime/canonical")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedActiveVersionId\":88}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/workflows/42/runtime/legacy")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedActiveVersionId\":88}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/workflows/42/versions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].versionNumber").value(1));
        mockMvc.perform(get("/api/workflows/42/runs")
                .queryParam("limit", "10")
                .queryParam("cursor", "frozen"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].runKey").value("canonical-301"))
            .andExpect(jsonPath("$.nextCursor").value("next"));
        mockMvc.perform(get("/api/workflows/42/runs/canonical-301"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runKey").value("canonical-301"))
            .andExpect(jsonPath("$.stepDetailAvailable").value(true));

        verify(workflowService).list(false);
        verify(workflowService).getById(42);
        verify(workflowService).versions(42);
        verify(workflowRunReadService).listRuns(42, 10, "frozen");
        verify(workflowRunReadService).getRun(42, "canonical-301");
    }

    @Test
    @WithMockUser
    void validationRejectsMissingBoundsUnknownFieldsAndScalarCoercion() throws Exception {
        mockMvc.perform(post("/api/workflows")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody().replace("\"Workflow\"", "17")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));
        mockMvc.perform(post("/api/workflows")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody().replaceFirst("\\{", "{\"runAsUserId\":41,")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));
        mockMvc.perform(post("/api/workflows")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody().replace("Workflow", "w".repeat(129))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.name").exists());
        mockMvc.perform(put("/api/workflows/42/draft")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody().replace("\"expectedRevision\":3", "\"expectedRevision\":\"3\"")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));
        mockMvc.perform(put("/api/workflows/42/draft")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody().replace("\"expectedRevision\":3", "\"expectedRevision\":-1")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.expectedRevision").exists());
        mockMvc.perform(post("/api/workflows/42/publish")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.expectedRevision").exists());
        mockMvc.perform(post("/api/workflows/42/simulate")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":\"3\",\"recordId\":91}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));
        mockMvc.perform(post("/api/workflows/42/simulate")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":3}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.recordId").exists());
        mockMvc.perform(post("/api/workflows")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));

        verifyNoInteractions(workflowService);
        verifyNoInteractions(simulationService);
    }

    @Test
    @WithMockUser
    void duplicateJsonFieldsAreRejectedBeforeControllerBinding() throws Exception {
        mockMvc.perform(post("/api/workflows")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody().replace(
                    "\"name\":\"Workflow\",",
                    "\"name\":\"Workflow\",\"name\":\"Shadow\",")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));
        mockMvc.perform(post("/api/workflows")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody().replace(
                    "\"schemaVersion\":1,",
                    "\"schemaVersion\":1,\"schemaVersion\":2,")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));
        mockMvc.perform(put("/api/workflows/42/draft")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody().replace(
                    "\"expectedRevision\":3,",
                    "\"expectedRevision\":3,\"expectedRevision\":4,")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));
        mockMvc.perform(post("/api/workflows/42/publish")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":3,\"expectedRevision\":4}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));

        verifyNoInteractions(workflowService);
    }

    @Test
    @WithMockUser
    void domainErrorsMapToTheExisting400403404And409Contracts() throws Exception {
        when(workflowService.getById(42))
            .thenThrow(new ResourceNotFoundException("Workflow not found"));
        when(workflowService.validate(42))
            .thenThrow(new BadRequestException("Workflow graph is incomplete"));
        when(workflowService.publish(any(Integer.class), any(WorkflowPublishRequest.class)))
            .thenThrow(new ConflictException("Workflow draft revision does not match"));
        when(workflowService.enable(42))
            .thenThrow(new ForbiddenException("Requires the RULE_MANAGE permission"));

        mockMvc.perform(get("/api/workflows/42"))
            .andExpect(status().isNotFound())
            .andExpect(content().string("Workflow not found"));
        mockMvc.perform(post("/api/workflows/42/validate").with(csrf().asHeader()))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Workflow graph is incomplete"));
        mockMvc.perform(post("/api/workflows/42/publish")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":3}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Workflow draft revision does not match"));
        mockMvc.perform(post("/api/workflows/42/enable").with(csrf().asHeader()))
            .andExpect(status().isForbidden())
            .andExpect(content().string("Requires the RULE_MANAGE permission"));
    }

    @Test
    @WithMockUser
    void unsupportedDeleteReplacementAndExecutionRoutesAreAbsent() throws Exception {
        mockMvc.perform(delete("/api/workflows/42").with(csrf().asHeader()))
            .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/workflows/42")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isMethodNotAllowed());

        Set<String> methodNames = Arrays.stream(WorkflowController.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());
        assertEquals(
            Set.of(
                "list", "create", "get", "saveDraft", "validate", "publish", "enable",
                "disable", "archive", "restore", "cutOverToCanonical", "rollBackToLegacy",
                "versions", "simulate", "resolveLegacyRule"),
            methodNames);

        verifyNoInteractions(workflowService);
    }

    @Test
    @WithMockUser
    void authenticatedMutationsRequireCsrf() throws Exception {
        mockMvc.perform(post("/api/workflows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/workflows/42/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workflows/42/validate"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workflows/42/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":3,\"recordId\":91}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workflows/42/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":3}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workflows/42/enable"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workflows/42/disable"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(workflowService);
    }

    @Test
    void authenticatedHttpSessionCanReadAndStillRequiresCsrfForMutation() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(new TestingAuthenticationToken("user", "credentials", "ROLE_USER")));
        when(workflowService.list(false)).thenReturn(List.of());
        when(workflowService.create(any(WorkflowCreateRequest.class))).thenReturn(workflow(false));

        mockMvc.perform(get("/api/workflows").session(session))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/workflows")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/workflows")
                .session(session)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody()))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser
    void oversizedWorkflowPayloadIsRejectedBeforeControllerBinding() throws Exception {
        mockMvc.perform(post("/api/workflows")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(513)))
            .andExpect(status().isContentTooLarge());

        verifyNoInteractions(workflowService);
    }

    @Test
    @WithAnonymousUser
    void anonymousReadsAndMutationsAreRejected() throws Exception {
        mockMvc.perform(get("/api/workflows"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/workflows/42/runs"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/workflows/42/enable").with(csrf().asHeader()))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(workflowService);
        verifyNoInteractions(workflowRunReadService);
    }

    private static WorkflowDto workflow(boolean enabled) {
        return new WorkflowDto(
            42,
            "Workflow",
            null,
            enabled,
            "legacy",
            null,
            3,
            "deal",
            "user",
            41,
            new WorkflowDefinition(1, null, List.of(), List.of()),
            new WorkflowCanvas(
                Map.of(),
                new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE)),
            88L,
            41,
            41,
            null,
            null);
    }

    private static WorkflowListItemDto listItem() {
        return new WorkflowListItemDto(
            42,
            "Workflow",
            null,
            false,
            "legacy",
            null,
            3,
            "deal",
            "user",
            41,
            new WorkflowListItemDto.ActiveVersion(88L, 1, null),
            4,
            1,
            null,
            41,
            41,
            null,
            null);
    }

    private static WorkflowVersionDto version() {
        return new WorkflowVersionDto(
            88L,
            1,
            "Workflow",
            null,
            "deal",
            "user",
            41,
            41,
            41,
            new WorkflowDefinition(1, null, List.of(), List.of()),
            new WorkflowCanvas(
                Map.of(),
                new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE)),
            null);
    }

    private static String createBody() {
        return """
            {
              "name":"Workflow",
              "recordType":"deal",
              "executionMode":"user",
              "definition":{"schemaVersion":1,"entryNodeId":null,"nodes":[],"edges":[]},
              "canvas":{"positions":{},"viewport":{"x":0,"y":0,"zoom":1}}
            }
            """;
    }

    private static String draftBody() {
        return """
            {
              "expectedRevision":3,
              "name":"Workflow",
              "recordType":"deal",
              "executionMode":"user",
              "definition":{"schemaVersion":1,"entryNodeId":null,"nodes":[],"edges":[]},
              "canvas":{"positions":{},"viewport":{"x":0,"y":0,"zoom":1}}
            }
            """;
    }

    @TestConfiguration
    static class MapperTestConfig {

        @Bean
        SqlSessionFactory sqlSessionFactory() {
            SqlSessionFactory sqlSessionFactory = mock(SqlSessionFactory.class);
            org.apache.ibatis.mapping.Environment environment = new org.apache.ibatis.mapping.Environment(
                "test",
                mock(org.apache.ibatis.transaction.TransactionFactory.class),
                mock(javax.sql.DataSource.class));
            when(sqlSessionFactory.getConfiguration())
                .thenReturn(new org.apache.ibatis.session.Configuration(environment));
            return sqlSessionFactory;
        }
    }
}
