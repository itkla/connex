package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.ai.AiGenerationAdapterService;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.config.LogoutAuditHandler;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.config.SecurityConfig;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.sso.CompositeClientRegistrationRepository;
import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;
import ooo.klae.connex.backend.util.ClientIpResolver;

@WebMvcTest(
    controllers = DealController.class,
    properties = {
        "connex.security.csrf-enabled=true",
        "connex.sso.enabled=false"
    }
)
@Import({
    SecurityConfig.class,
    RequestBodySizeProperties.class,
    DealControllerTest.MapperTestConfig.class
})
@WithMockUser
class DealControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private DealService dealService;
    @MockitoBean private BulkOperationService bulkOperationService;
    @MockitoBean private DealRiskService dealRiskService;
    @MockitoBean private AiGenerationAdapterService aiGenerationAdapterService;
    @MockitoBean private WorkspaceService workspaceService;
    @MockitoBean private MemberScopeResolver memberScopeResolver;
    @MockitoBean private CompositeClientRegistrationRepository clientRegistrationRepository;
    @MockitoBean private SocialLoginClientRegistrations socialLoginClientRegistrations;
    @MockitoBean private DbRelyingPartyRegistrationRepository relyingPartyRegistrationRepository;
    @MockitoBean private SsoAuthenticationSuccessHandler ssoAuthenticationSuccessHandler;
    @MockitoBean private SessionSecurityService sessionSecurityService;
    @MockitoBean private BusinessCardRateLimiter businessCardRateLimiter;
    @MockitoBean private CapabilityEntitlement capabilityEntitlement;
    @MockitoBean private ClientAssertedCorrelationPseudonymizer correlationPseudonymizer;
    @MockitoBean private TenantCatalogResolver tenantCatalogResolver;
    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private WorkspaceCookie workspaceCookie;
    @MockitoBean private WorkspaceRequestResolver workspaceRequestResolver;
    @MockitoBean private OneTimeLinkFlowCookie oneTimeLinkFlowCookie;
    @MockitoBean private LogoutAuditHandler logoutAuditHandler;
    @MockitoBean private LoginRateLimiter loginRateLimiter;
    @MockitoBean private ClientIpResolver clientIpResolver;
    @MockitoBean private ErrorReporter errorReporter;

    @Test
    void createDelegatesTheWriteOnlyDuplicateReviewToken() throws Exception {
        Deal deal = deal();
        String reviewToken = "a".repeat(64);
        when(dealService.createReviewed(any(Deal.class), eq(reviewToken)))
            .thenReturn(deal);

        mockMvc.perform(post("/api/deals")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Renewal",
                      "value":1000.00,
                      "actualValue":0.00,
                      "currency":"USD",
                      "pipeline":3,
                      "stage":11,
                      "company":18,
                      "duplicateReviewToken":"%s"
                    }
                    """.formatted(reviewToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.duplicateReviewToken").doesNotExist());

        verify(dealService).createReviewed(any(Deal.class), eq(reviewToken));
    }

    @Test
    void malformedDuplicateReviewTokenReturnsBadRequestWithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/api/deals")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Renewal",
                      "value":1000.00,
                      "actualValue":0.00,
                      "currency":"USD",
                      "pipeline":3,
                      "stage":11,
                      "company":18,
                      "duplicateReviewToken":"invalid"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.duplicateReviewToken").exists());

        verifyNoInteractions(dealService);
    }

    @Test
    void updateNameDelegatesAndSerializesTheFullDeal() throws Exception {
        Deal deal = deal();
        deal.setName("FY27 Renewal");
        when(dealService.updateName(42, "FY27 Renewal")).thenReturn(deal);

        MvcResult result = mockMvc.perform(put("/api/deals/42/name")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"FY27 Renewal"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.workspaceId").value(7))
            .andExpect(jsonPath("$.ownerId").value(9))
            .andExpect(jsonPath("$.name").value("FY27 Renewal"))
            .andExpect(jsonPath("$.pipeline").value(3))
            .andExpect(jsonPath("$.stage").value(11))
            .andExpect(jsonPath("$.references").isArray())
            .andReturn();
        assertDecimal("1000.00", objectMapper.readTree(
            result.getResponse().getContentAsString()).get("value"));

        verify(dealService).updateName(42, "FY27 Renewal");
    }

    @Test
    void updateValueDelegatesWithAnExactBigDecimal() throws Exception {
        Deal deal = deal();
        deal.setValue(new BigDecimal("125000.00"));
        when(dealService.updateValue(42, new BigDecimal("125000.00"))).thenReturn(deal);

        MvcResult result = mockMvc.perform(put("/api/deals/42/value")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"value":125000.00}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
            .andReturn();
        assertDecimal("125000.00", objectMapper.readTree(
            result.getResponse().getContentAsString()).get("value"));

        verify(dealService).updateValue(42, new BigDecimal("125000.00"));
    }

    @Test
    void closeDelegatesNullableActualValueAsAnExactBigDecimal() throws Exception {
        Deal deal = deal();
        deal.setWon(true);
        deal.setActualValue(new BigDecimal("12.34"));
        when(dealService.close(42, true, "signed", new BigDecimal("12.34")))
            .thenReturn(deal);

        MvcResult result = mockMvc.perform(post("/api/deals/42/close")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"won":true,"reason":"signed","actualValue":12.34}
                    """))
            .andExpect(status().isOk())
            .andReturn();
        assertDecimal("12.34", objectMapper.readTree(
            result.getResponse().getContentAsString()).get("actualValue"));

        verify(dealService).close(42, true, "signed", new BigDecimal("12.34"));
    }

    @Test
    void closeRejectsANegativeActualValueWithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/api/deals/42/close")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"won":false,"reason":"cancelled","actualValue":-12.34}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.actualValue").exists());

        verifyNoInteractions(dealService);
    }

    @Test
    void closePreservesAnOmittedActualValue() throws Exception {
        Deal deal = deal();
        when(dealService.close(42, true, null, null)).thenReturn(deal);

        mockMvc.perform(post("/api/deals/42/close")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"won":true}
                    """))
            .andExpect(status().isOk());

        verify(dealService).close(42, true, null, null);
    }

    @Test
    void invalidNamesReturnBadRequestWithoutCallingTheService() throws Exception {
        String oversized = objectMapper.writeValueAsString(Map.of("name", "x".repeat(256)));
        for (String body : List.of("{}", "{\"name\":\"\"}", "{\"name\":\"   \"}", oversized)) {
            mockMvc.perform(put("/api/deals/42/name")
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.name").exists());
        }

        verifyNoInteractions(dealService);
    }

    @Test
    void invalidValuesReturnBadRequestWithoutCallingTheService() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"value\":null}",
                "{\"value\":-0.01}",
                "{\"value\":10000000000000.00}",
                "{\"value\":1.001}")) {
            mockMvc.perform(put("/api/deals/42/value")
                    .with(csrf().asHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.value").exists());
        }

        verifyNoInteractions(dealService);
    }

    @Test
    void malformedJsonReturnsBadRequestWithoutCallingTheService() throws Exception {
        mockMvc.perform(put("/api/deals/42/value")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("Malformed request body"));

        verifyNoInteractions(dealService);
    }

    @Test
    void missingDealReturnsExistingPlainTextNotFoundResponse() throws Exception {
        when(dealService.updateName(42, "Missing"))
            .thenThrow(new ResourceNotFoundException("Deal not found with id: 42"));

        mockMvc.perform(put("/api/deals/42/name")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Missing"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(content().string("Deal not found with id: 42"));
    }

    @Test
    void lineItemValueConflictReturnsJsonMessage() throws Exception {
        String message =
            "Cannot manually edit the deal value while line items exist; update or remove the line items first";
        when(dealService.updateValue(eq(42), eq(new BigDecimal("125000.00"))))
            .thenThrow(new ConflictException(message));

        mockMvc.perform(put("/api/deals/42/value")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"value":125000.00}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(message));
    }

    @Test
    void authenticatedMutationsRequireCsrf() throws Exception {
        mockMvc.perform(put("/api/deals/42/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"FY27 Renewal"}
                    """))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/deals/42/value")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"value":125000.00}
                    """))
            .andExpect(status().isForbidden());

        verifyNoInteractions(dealService);
    }

    @Test
    @WithAnonymousUser
    void anonymousMutationsAreRejectedEvenWithCsrf() throws Exception {
        mockMvc.perform(put("/api/deals/42/name")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"FY27 Renewal"}
                    """))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/deals/42/value")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"value":125000.00}
                    """))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(dealService);
    }

    private Deal deal() {
        Deal deal = new Deal();
        deal.setId(42);
        deal.setWorkspaceId(7);
        deal.setOwnerId(9);
        deal.setName("Renewal");
        deal.setValue(new BigDecimal("1000.00"));
        deal.setActualValue(new BigDecimal("0.00"));
        deal.setCurrency("USD");
        deal.setPipelineId(3);
        deal.setStageId(11);
        deal.setPosition(2);
        deal.setCompanyId(18);
        deal.setExpectedCloseDate("2027-03-31");
        deal.setCreatedAt("2026-07-01 12:00:00");
        deal.setUpdatedAt("2026-07-19 18:00:00");
        return deal;
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()));
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
