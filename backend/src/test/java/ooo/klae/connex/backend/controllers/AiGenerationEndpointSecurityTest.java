package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ooo.klae.connex.backend.ai.AiGenerationAdapterService;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.config.LogoutAuditHandler;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.config.SecurityConfig;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.dto.BusinessCardAvailabilityResponse;
import ooo.klae.connex.backend.dto.AiGenerationStatusDto;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.BusinessCardService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.IntroductionService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WarmPathService;
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
import ooo.klae.connex.backend.webauthn.WebAuthnService;

@WebMvcTest(
    controllers = {
        AiGenerationController.class,
        BusinessCardController.class,
        DealController.class,
        IntroductionController.class
    },
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = GlobalExceptionHandler.class
    ),
    properties = {
        "connex.security.csrf-enabled=true",
        "connex.sso.enabled=false"
    }
)
@Import({ SecurityConfig.class, AiGenerationEndpointSecurityTest.MapperTestConfig.class })
@WithMockUser
class AiGenerationEndpointSecurityTest {
    @Autowired private MockMvc mockMvc;

    @MockitoBean private DealService dealService;
    @MockitoBean private BulkOperationService bulkOperationService;
    @MockitoBean private DealRiskService dealRiskService;
    @MockitoBean private AiGenerationAdapterService aiGenerationAdapterService;
    @MockitoBean private AiGenerationService aiGenerationService;
    @MockitoBean private BusinessCardService businessCardService;
    @MockitoBean private WorkspaceService workspaceService;
    @MockitoBean private MemberScopeResolver memberScopeResolver;
    @MockitoBean private IntroductionService introductionService;
    @MockitoBean private WarmPathService warmPathService;
    @MockitoBean private CompositeClientRegistrationRepository clientRegistrationRepository;
    @MockitoBean private SocialLoginClientRegistrations socialLoginClientRegistrations;
    @MockitoBean private DbRelyingPartyRegistrationRepository relyingPartyRegistrationRepository;
    @MockitoBean private SsoAuthenticationSuccessHandler ssoAuthenticationSuccessHandler;
    @MockitoBean private RequestBodySizeProperties requestBodySizeProperties;
    @MockitoBean private SessionSecurityService sessionSecurityService;
    @MockitoBean private PrivilegedMfaProperties privilegedMfaProperties;
    @MockitoBean private PrivilegedAccountService privilegedAccountService;
    @MockitoBean private WebAuthnService webAuthnService;
    @MockitoBean private AuditService auditService;
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

    @Test
    void providerGeneratingEndpointsRejectGetAndRequireCsrfForPost() throws Exception {
        assertPostWithCsrfOnly("/api/deals/17/brief");
        assertPostWithCsrfOnly("/api/deals/17/rationale");
        assertPostWithCsrfOnly("/api/introductions/suggestions/rationale?personA=11&personB=12");

        verify(aiGenerationAdapterService).startDealBrief(17, false);
        verify(aiGenerationAdapterService).startDealRationale(17, false);
        verify(aiGenerationAdapterService).startIntroRationale(11, 12);
    }

    @Test
    void dealRegenerationRemainsPostOnlyAndCsrfProtected() throws Exception {
        assertPostWithCsrfOnly("/api/deals/17/brief?refresh=true");
        assertPostWithCsrfOnly("/api/deals/17/rationale?refresh=true");

        verify(aiGenerationAdapterService).startDealBrief(17, true);
        verify(aiGenerationAdapterService).startDealRationale(17, true);
    }

    @Test
    void generationStatusIsAuthenticatedReadOnlyAndNoStore() throws Exception {
        String handle = "f40f5943-9943-4c79-94d2-2e2a014cff46";
        when(aiGenerationService.status(handle)).thenReturn(new AiGenerationStatusDto(
                handle, "deal.brief", "running", null, null, 2_000, 120_000,
                "2026-08-08T10:02:00Z"));

        mockMvc.perform(get("/api/ai/generations/{handle}", handle))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"));

        verify(aiGenerationService).status(handle);
    }

    @Test
    @WithAnonymousUser
    void businessCardAvailabilityRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/business-cards/availability"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void businessCardAvailabilityIsAReadOnlyAuthenticatedEndpoint() throws Exception {
        when(businessCardService.availability())
                .thenReturn(new BusinessCardAvailabilityResponse(true, true));

        mockMvc.perform(get("/api/business-cards/availability"))
                .andExpect(status().isOk());

        verify(businessCardService).availability();
    }

    private void assertPostWithCsrfOnly(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post(path))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path).with(csrf().asHeader()))
                .andExpect(status().isAccepted());
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
