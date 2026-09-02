package ooo.klae.connex.backend.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ooo.klae.connex.backend.ai.AiGenerationAdapterService;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.config.LogoutAuditHandler;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.config.SecurityConfig;
import ooo.klae.connex.backend.exceptions.DuplicateReviewException;
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
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

@WebMvcTest(
    controllers = LegacyRecordCreationController.class,
    properties = {
        "connex.sso.enabled=false",
        "connex.record-creation.guided-cutover-enabled=false"
    }
)
@Import({SecurityConfig.class,
    RequestBodySizeProperties.class,
    LegacyRecordCreationControllerTest.MapperTestConfig.class})
@WithMockUser
class LegacyRecordCreationControllerTest {
    private static final String LEGACY_STALE_MESSAGE =
        "Duplicate candidates changed before creation; review them again";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PersonService personService;
    @MockitoBean private CompanyService companyService;
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
    @MockitoBean private UserMapper userMapper;
    @MockitoBean private WebSocketSessionRegistry webSocketSessions;
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
    @MockitoBean private ErrorReporter errorReporter;

    @Test
    void dealCreateDelegatesTheWriteOnlyDuplicateReviewToken() throws Exception {
        Deal deal = deal();
        String reviewToken = "a".repeat(64);
        when(dealService.createReviewed(any(Deal.class), eq(reviewToken)))
            .thenReturn(deal);

        mockMvc.perform(post("/api/deals")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(dealBody(reviewToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.duplicateReviewToken").doesNotExist());

        verify(dealService).createReviewed(any(Deal.class), eq(reviewToken));
    }

    @Test
    void malformedDealDuplicateReviewTokenReturnsBadRequestWithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/api/deals")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(dealBody("invalid")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.duplicateReviewToken").exists());

        verifyNoInteractions(dealService);
    }

    @Test
    void personCreateDelegatesTheWriteOnlyDuplicateReviewToken() throws Exception {
        Person person = new Person();
        person.setId(43);
        person.setName("Ada");
        String reviewToken = "b".repeat(64);
        when(personService.createReviewed(any(Person.class), eq(reviewToken)))
            .thenReturn(person);

        mockMvc.perform(post("/api/persons")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(personBody(reviewToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(43))
            .andExpect(jsonPath("$.duplicateReviewToken").doesNotExist());

        verify(personService).createReviewed(any(Person.class), eq(reviewToken));
    }

    @Test
    void malformedPersonDuplicateReviewTokenReturnsBadRequestWithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/api/persons")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(personBody("invalid")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.duplicateReviewToken").exists());

        verifyNoInteractions(personService);
    }

    @Test
    void companyCreateDelegatesTheWriteOnlyDuplicateReviewToken() throws Exception {
        Company company = new Company();
        company.setId(44);
        company.setName("Analytical Engines");
        String reviewToken = "c".repeat(64);
        when(companyService.createCompanyReviewed(any(Company.class), eq(reviewToken)))
            .thenReturn(company);

        mockMvc.perform(post("/api/companies")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(companyBody(reviewToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(44))
            .andExpect(jsonPath("$.duplicateReviewToken").doesNotExist());

        verify(companyService).createCompanyReviewed(any(Company.class), eq(reviewToken));
    }

    @Test
    void malformedCompanyDuplicateReviewTokenReturnsBadRequestWithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/api/companies")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(companyBody("invalid")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.duplicateReviewToken").exists());

        verifyNoInteractions(companyService);
    }

    @Test
    void personDuplicateConflictPreservesLegacyResponseShape() throws Exception {
        String reviewToken = "d".repeat(64);
        when(personService.createReviewed(any(Person.class), eq(reviewToken)))
            .thenThrow(new DuplicateReviewException("DUPLICATE_REVIEW_STALE", LEGACY_STALE_MESSAGE));

        mockMvc.perform(post("/api/persons")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(personBody(reviewToken)))
            .andExpect(status().isConflict())
            .andExpect(content().string(
                "{\"message\":\"" + LEGACY_STALE_MESSAGE + "\"}"))
            .andExpect(jsonPath("$.message").value(LEGACY_STALE_MESSAGE))
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void companyDuplicateConflictPreservesLegacyResponseShape() throws Exception {
        String reviewToken = "e".repeat(64);
        when(companyService.createCompanyReviewed(any(Company.class), eq(reviewToken)))
            .thenThrow(new DuplicateReviewException("DUPLICATE_REVIEW_STALE", LEGACY_STALE_MESSAGE));

        mockMvc.perform(post("/api/companies")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(companyBody(reviewToken)))
            .andExpect(status().isConflict())
            .andExpect(content().string(
                "{\"message\":\"" + LEGACY_STALE_MESSAGE + "\"}"))
            .andExpect(jsonPath("$.message").value(LEGACY_STALE_MESSAGE))
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    private static String personBody(String reviewToken) {
        return """
            {"name":"Ada","email":"ada@example.com","duplicateReviewToken":"%s"}
            """.formatted(reviewToken);
    }

    private static String companyBody(String reviewToken) {
        return """
            {"name":"Analytical Engines","duplicateReviewToken":"%s"}
            """.formatted(reviewToken);
    }

    private static String dealBody(String reviewToken) {
        return """
            {"name":"Renewal","value":1000.00,"actualValue":0.00,"currency":"USD",
             "pipeline":3,"stage":11,"company":18,"duplicateReviewToken":"%s"}
            """.formatted(reviewToken);
    }

    private static Deal deal() {
        Deal deal = new Deal();
        deal.setId(42);
        deal.setWorkspaceId(7);
        deal.setOwnerId(9);
        deal.setName("Renewal");
        deal.setValue(new BigDecimal("1000.00"));
        deal.setActualValue(BigDecimal.ZERO);
        deal.setCurrency("USD");
        deal.setPipelineId(3);
        deal.setStageId(11);
        deal.setCompanyId(18);
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
