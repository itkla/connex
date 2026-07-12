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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ooo.klae.connex.backend.ai.brief.DealBriefService;
import ooo.klae.connex.backend.ai.introrationale.IntroRationaleService;
import ooo.klae.connex.backend.ai.riskrationale.DealRiskRationaleService;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.config.SecurityConfig;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.IntroductionService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.sso.CompositeClientRegistrationRepository;
import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;

@WebMvcTest(
    controllers = { DealController.class, IntroductionController.class },
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
    @MockitoBean private DealBriefService dealBriefService;
    @MockitoBean private DealRiskRationaleService dealRiskRationaleService;
    @MockitoBean private WorkspaceService workspaceService;
    @MockitoBean private IntroductionService introductionService;
    @MockitoBean private IntroRationaleService introRationaleService;
    @MockitoBean private CompositeClientRegistrationRepository clientRegistrationRepository;
    @MockitoBean private SocialLoginClientRegistrations socialLoginClientRegistrations;
    @MockitoBean private DbRelyingPartyRegistrationRepository relyingPartyRegistrationRepository;
    @MockitoBean private SsoAuthenticationSuccessHandler ssoAuthenticationSuccessHandler;
    @MockitoBean private RequestBodySizeProperties requestBodySizeProperties;
    @MockitoBean private SessionSecurityService sessionSecurityService;
    @MockitoBean private TenantCatalogResolver tenantCatalogResolver;
    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private WorkspaceCookie workspaceCookie;

    @Test
    void providerGeneratingEndpointsRejectGetAndRequireCsrfForPost() throws Exception {
        assertPostWithCsrfOnly("/api/deals/17/brief");
        assertPostWithCsrfOnly("/api/deals/17/rationale");
        assertPostWithCsrfOnly("/api/introductions/suggestions/rationale?personA=11&personB=12");

        verify(dealBriefService).generate(17, false);
        verify(dealRiskRationaleService).generate(17, false);
        verify(introRationaleService).generate(11, 12);
    }

    @Test
    void dealRegenerationRemainsPostOnlyAndCsrfProtected() throws Exception {
        assertPostWithCsrfOnly("/api/deals/17/brief?refresh=true");
        assertPostWithCsrfOnly("/api/deals/17/rationale?refresh=true");

        verify(dealBriefService).generate(17, true);
        verify(dealRiskRationaleService).generate(17, true);
    }

    private void assertPostWithCsrfOnly(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post(path))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path).with(csrf()))
                .andExpect(status().isOk());
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
