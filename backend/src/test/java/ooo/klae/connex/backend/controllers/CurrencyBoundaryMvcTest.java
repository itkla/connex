package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.services.CurrencyFilterService;
import ooo.klae.connex.backend.ai.AiGenerationAdapterService;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.exceptions.InvalidApprovalCurrencyException;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.ApprovalPolicyService;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.ExportService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.WarmthFilterResolver;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;

class CurrencyBoundaryMvcTest {
    private DealService deals;
    private ExportService exports;
    private ApprovalPolicyService policies;
    private MockMvc mvc;
    private DealMapper buckets;

    @BeforeEach
    void setup() {
        deals = mock(DealService.class, invocation -> switch (invocation.getMethod().getName()) {
            case "queryDealsPage", "querySegmentDealsPage" -> new PageResponse<>(List.of(), 0);
            default -> Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        exports = mock(ExportService.class, invocation -> "");
        policies = mock(ApprovalPolicyService.class);
        WorkspaceService workspace = mock(WorkspaceService.class);
        buckets = mock(DealMapper.class);
        var currencyFilters = new CurrencyFilterService(buckets, workspace);
        MemberScopeResolver scopes = mock(MemberScopeResolver.class);
        when(workspace.getCurrentAnalyticsTimezone()).thenReturn("UTC");
        when(scopes.resolve(any(), any(), anyInt())).thenReturn(MemberScope.allTeam());
        mvc = MockMvcBuilders.standaloneSetup(
            new DealController(deals, mock(BulkOperationService.class), mock(DealRiskService.class),
                mock(AiGenerationAdapterService.class), workspace, currencyFilters, scopes),
            new ExportController(exports, scopes, mock(WarmthFilterResolver.class), workspace, currencyFilters),
            new ApprovalPolicyController(policies),
            new LegacyRecordCreationController(mock(PersonService.class), mock(CompanyService.class), deals))
            .setControllerAdvice(new GlobalExceptionHandler(mock(ErrorReporter.class), mock(TenantContext.class)))
            .build();
    }

    private static Stream<String> endpoints() {
        return Stream.of("/api/deals/page", "/api/deals/metrics", "/api/deals/ids",
            "/api/deals/revenue-timeseries", "/api/deals/revenue-series", "/api/deals/stage-distribution",
            "/api/deals/kpis", "/api/deals/pipeline-value", "/api/deals/aging", "/api/deals/top",
            "/api/exports/deals", "/api/deals/segment/page", "/api/deals/segment/metrics",
            "/api/deals/segment/ids", "/api/exports/deals/segment");
    }

    private static Stream<Arguments> invalidFilters() {
        return endpoints().flatMap(path -> Stream.of("", " ", "yen", "JP", "JPYY", "JP1", "XXX", "XTS")
            .map(currency -> Arguments.of(path, currency)));
    }

    private MockHttpServletRequestBuilder filterRequest(String path, String currency) {
        if (path.contains("segment")) {
            return post(path).contentType(MediaType.APPLICATION_JSON).content(
                "{\"definition\":{\"match\":\"all\",\"conditions\":[]}" +
                (currency == null ? "" : ",\"currency\":\"" + currency + "\"") + "}");
        }
        MockHttpServletRequestBuilder request = get(path)
            .param("from", "2026-01-01").param("to", "2026-01-31").param("granularity", "month");
        return currency == null ? request : request.param("currency", currency);
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void everyMoneyFilterPreservesExistingLegacyBuckets(String path) throws Exception {
        for (String currency : List.of("X-LOCAL", "US$", "USDT", " jpy ")) {
            when(buckets.countsByCurrency(0)).thenReturn(List.of(
                new FacetCount(currency, 1, null)));
            mvc.perform(filterRequest(path, currency)).andExpect(status().isOk());
            Object service = path.startsWith("/api/exports") ? exports : deals;
            assertEquals(currency, mockingDetails(service).getInvocations().iterator().next()
                .getArguments()[currencyArgumentIndex(path)]);
            org.mockito.Mockito.clearInvocations(deals, exports);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidFilters")
    void everyMoneyFilterRejectsSuppliedInvalidCurrency(String path, String currency) throws Exception {
        mvc.perform(filterRequest(path, currency)).andExpect(status().isBadRequest());
        verifyNoInteractions(deals, exports);
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void everyMoneyFilterCanonicalizesValidCurrency(String path) throws Exception {
        mvc.perform(filterRequest(path, " jpy ")).andExpect(status().isOk());
        Object service = path.startsWith("/api/exports") ? exports : deals;
        assertEquals("JPY", mockingDetails(service).getInvocations().iterator().next()
            .getArguments()[currencyArgumentIndex(path)]);
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void everyMoneyFilterPreservesAbsentCurrency(String path) throws Exception {
        mvc.perform(filterRequest(path, null)).andExpect(status().isOk());
        Object service = path.startsWith("/api/exports") ? exports : deals;
        assertEquals(1, mockingDetails(service).getInvocations().size());
        assertNull(mockingDetails(service).getInvocations().iterator().next()
            .getArguments()[currencyArgumentIndex(path)]);
    }

    private int currencyArgumentIndex(String path) {
        if (path.equals("/api/deals/segment/page")) return 4;
        if (path.contains("segment")) return 2;
        if (path.equals("/api/deals/page")) return 3;
        if (path.equals("/api/deals/metrics") || path.equals("/api/deals/ids")
                || path.equals("/api/exports/deals")) return 1;
        return 0;
    }

    private static Stream<Arguments> invalidWrites() {
        return Stream.concat(
            Stream.of("yen", "JP1", "USDT", "XXX", "XTS").map(currency -> Arguments.of("/api/deals", currency)),
            Stream.of("", " ", "yen", "JP1", "XXX", "XTS")
                .map(currency -> Arguments.of("/api/approval-policies", currency)));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "  "})
    void missingDealCreationCurrencyReachesDefaultingServiceButUpdatesRefuseIt(String currency) throws Exception {
        when(deals.createReviewed(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        String body = "{\"name\":\"Default currency\",\"value\":100,\"pipeline\":1,\"stage\":1"
            + (currency == null ? "" : ",\"currency\":\"" + currency + "\"") + "}";
        mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        assertEquals(currency, ((ooo.klae.connex.backend.beans.Deal) mockingDetails(deals).getInvocations()
            .iterator().next().getArguments()[0]).getCurrency());
        org.mockito.Mockito.clearInvocations(deals);
        mvc.perform(put("/api/deals/42").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.currency").exists());
        verifyNoInteractions(deals);
    }

    @ParameterizedTest
    @MethodSource("invalidWrites")
    void dealAndPolicyWritesRejectInvalidCurrencies(String path, String currency) throws Exception {
        MockHttpServletRequestBuilder request = path.endsWith("/42") ? put(path) : post(path);
        String fields = path.startsWith("/api/deals") ? "\"value\":100,\"pipeline\":1,\"stage\":1," : "";
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(
            "{\"name\":\"Currency test\"," + fields + "\"currency\":\"" + currency + "\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.currency").exists());
        verifyNoInteractions(deals, policies);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"X-LOCAL", "US$", " jpy "})
    void existingDealAndPolicyRequestsBindUnchangedStoredCurrencies(String currency) throws Exception {
        when(deals.update(org.mockito.ArgumentMatchers.eq(42), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(policies.update(org.mockito.ArgumentMatchers.eq(42), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        String dealBody = "{\"name\":\"Legacy deal\",\"value\":100,\"pipeline\":1,\"stage\":1,\"currency\":\"" + currency + "\"}";
        String policyBody = "{\"name\":\"Legacy policy\",\"minTotal\":100,\"currency\":\"" + currency + "\"}";
        mvc.perform(put("/api/deals/42").contentType(MediaType.APPLICATION_JSON).content(dealBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value(currency));
        mvc.perform(put("/api/approval-policies/42").contentType(MediaType.APPLICATION_JSON).content(policyBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value(currency));
        mvc.perform(post("/api/approval-policies/42/impact").contentType(MediaType.APPLICATION_JSON).content(policyBody))
            .andExpect(status().isOk());
    }

    @Test
    void invalidSavedConfigurationReturnsALocalizableConflictCode() {
        InvalidApprovalCurrencyException exception = new InvalidApprovalCurrencyException();
        var response = new GlobalExceptionHandler(mock(ErrorReporter.class), mock(TenantContext.class))
            .invalidApprovalCurrency(exception);
        assertEquals(409, response.getStatusCode().value());
        assertEquals(java.util.Map.of("code", InvalidApprovalCurrencyException.CODE,
            "message", exception.getMessage()), response.getBody());
    }

    @Test
    void savedPolicyValidityIsReadOnly() throws Exception {
        when(policies.create(any())).thenAnswer(invocation -> {
            ooo.klae.connex.backend.beans.ApprovalPolicy policy = invocation.getArgument(0);
            policy.setCurrency(ooo.klae.connex.backend.validation.CurrencyCodes.optional(policy.getCurrency()));
            return policy;
        });
        mvc.perform(post("/api/approval-policies").contentType(MediaType.APPLICATION_JSON).content(
            "{\"name\":\"Currency test\",\"minTotal\":100,\"currency\":\" jpy \",\"currencyValid\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("JPY"))
            .andExpect(jsonPath("$.currencyValid").value(true));
    }
}
