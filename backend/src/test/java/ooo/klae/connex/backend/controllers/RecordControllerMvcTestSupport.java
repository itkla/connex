package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.ai.AiGenerationAdapterService;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.config.LogoutAuditHandler;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.config.SecurityConfig;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.WebSocketSessionRegistry;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.sso.CompositeClientRegistrationRepository;
import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;
import ooo.klae.connex.backend.sso.SsoHttpClient;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/** Identical MVC, security, and mapper wiring for record creation and deal mutation tests. */
@WebMvcTest(
    controllers = {DealController.class, LegacyRecordCreationController.class},
    properties = {
        "connex.sso.enabled=false",
        "connex.record-creation.guided-cutover-enabled=false"
    }
)
@Import({SecurityConfig.class,
    RequestBodySizeProperties.class,
    RecordControllerMvcTestSupport.MapperTestConfig.class})
abstract class RecordControllerMvcTestSupport {
    @MockitoBean protected PersonService personService;
    @MockitoBean protected CompanyService companyService;
    @MockitoBean protected DealService dealService;
    @MockitoBean protected BulkOperationService bulkOperationService;
    @MockitoBean protected DealRiskService dealRiskService;
    @MockitoBean protected AiGenerationAdapterService aiGenerationAdapterService;
    @MockitoBean protected WorkspaceService workspaceService;
    @MockitoBean protected MemberScopeResolver memberScopeResolver;
    @MockitoBean protected CompositeClientRegistrationRepository clientRegistrationRepository;
    @MockitoBean protected SocialLoginClientRegistrations socialLoginClientRegistrations;
    @MockitoBean protected SsoHttpClient ssoHttpClient;
    @MockitoBean protected DbRelyingPartyRegistrationRepository relyingPartyRegistrationRepository;
    @MockitoBean protected SsoAuthenticationSuccessHandler ssoAuthenticationSuccessHandler;
    @MockitoBean protected SessionSecurityService sessionSecurityService;
    @MockitoBean protected UserMapper userMapper;
    @MockitoBean protected WebSocketSessionRegistry webSocketSessions;
    @MockitoBean protected PrivilegedMfaProperties privilegedMfaProperties;
    @MockitoBean protected PrivilegedAccountService privilegedAccountService;
    @MockitoBean protected WebAuthnService webAuthnService;
    @MockitoBean protected AuditService auditService;
    @MockitoBean protected BusinessCardRateLimiter businessCardRateLimiter;
    @MockitoBean protected CapabilityEntitlement capabilityEntitlement;
    @MockitoBean protected ClientAssertedCorrelationPseudonymizer correlationPseudonymizer;
    @MockitoBean protected TenantCatalogResolver tenantCatalogResolver;
    @MockitoBean protected TenantContext tenantContext;
    @MockitoBean protected WorkspaceCookie workspaceCookie;
    @MockitoBean protected WorkspaceRequestResolver workspaceRequestResolver;
    @MockitoBean protected OneTimeLinkFlowCookie oneTimeLinkFlowCookie;
    @MockitoBean protected LogoutAuditHandler logoutAuditHandler;
    @MockitoBean protected LoginRateLimiter loginRateLimiter;
    @MockitoBean protected ClientIpResolver clientIpResolver;
    @MockitoBean protected ErrorReporter errorReporter;

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
