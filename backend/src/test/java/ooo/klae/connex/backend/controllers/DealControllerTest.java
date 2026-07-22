package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.ai.brief.DealBriefService;
import ooo.klae.connex.backend.ai.riskrationale.DealRiskRationaleService;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.config.SecurityConfig;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
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
    @MockitoBean private DealBriefService dealBriefService;
    @MockitoBean private DealRiskRationaleService dealRiskRationaleService;
    @MockitoBean private WorkspaceService workspaceService;
    @MockitoBean private MemberScopeResolver memberScopeResolver;
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
    @MockitoBean private WorkspaceRequestResolver workspaceRequestResolver;

    @Test
    void updateNameDelegatesAndSerializesTheFullDeal() throws Exception {
        Deal deal = deal();
        deal.setName("FY27 Renewal");
        when(dealService.updateName(42, "FY27 Renewal")).thenReturn(deal);

        mockMvc.perform(put("/api/deals/42/name")
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
            .andExpect(jsonPath("$.value").value(1000.0))
            .andExpect(jsonPath("$.pipeline").value(3))
            .andExpect(jsonPath("$.stage").value(11))
            .andExpect(jsonPath("$.references").isArray());

        verify(dealService).updateName(42, "FY27 Renewal");
    }

    @Test
    void updateValueDelegatesWithAnExactBigDecimal() throws Exception {
        Deal deal = deal();
        deal.setValue(125000.0);
        when(dealService.updateValue(42, new BigDecimal("125000.00"))).thenReturn(deal);

        mockMvc.perform(put("/api/deals/42/value")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"value":125000.00}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.value").value(125000.0));

        verify(dealService).updateValue(42, new BigDecimal("125000.00"));
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
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/deals/42/value")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"value":125000.00}
                    """))
            .andExpect(status().isForbidden());

        verifyNoInteractions(dealService);
    }

    private Deal deal() {
        Deal deal = new Deal();
        deal.setId(42);
        deal.setWorkspaceId(7);
        deal.setOwnerId(9);
        deal.setName("Renewal");
        deal.setValue(1000.0);
        deal.setActualValue(0.0);
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
