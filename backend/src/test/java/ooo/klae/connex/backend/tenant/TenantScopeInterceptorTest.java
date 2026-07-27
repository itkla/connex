package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.exceptions.ForbiddenException;

class TenantScopeInterceptorTest {

    private static final String NS = "ooo.klae.connex.backend.mappers.";

    private final TenantContext tenantContext = new TenantContext();
    private final TenantScopeInterceptor interceptor = new TenantScopeInterceptor(tenantContext, true, false);

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void routedModeRequiresACatalogScopeOffTheRequestThread() {
        TenantScopeInterceptor routed = new TenantScopeInterceptor(tenantContext, true, true);

        assertThrows(IllegalStateException.class,
            () -> routed.enforce(NS + "CompanyMapper.getAllCompanies"),
            "an off-thread scoped statement with no catalog scope must fail loudly when routing is enabled");

        tenantContext.swapCatalogOverride(java.util.Optional.of("cnx_a"));
        try {
            routed.enforce(NS + "CompanyMapper.getAllCompanies");
        } finally {
            tenantContext.swapCatalogOverride(null);
        }
        routed.enforce(NS + "WorkspaceMapper.getRole");
    }

    @Test
    void scopedStatementsRequireResolvedContext() {
        assertTrue(interceptor.requiresResolvedContext(NS + "CompanyMapper.getAllCompanies"));
        assertTrue(interceptor.requiresResolvedContext(
            NS + "IdentityMapper.findPersonBackfillCandidates"));
        assertTrue(interceptor.requiresResolvedContext(
            NS + "IdentityCollisionMapper.findVisibleGroups"));
        assertTrue(interceptor.requiresResolvedContext(NS + "DealMapper.search"));
        assertTrue(interceptor.requiresResolvedContext(NS + "ReportMapper.getDefinitions"));
        assertTrue(interceptor.requiresResolvedContext(NS + "SavedViewPreferenceMapper.getPin"));
        assertTrue(interceptor.requiresResolvedContext(NS + "SavedViewPreferenceMapper.deletePinsForUser"));
        assertTrue(interceptor.requiresResolvedContext(NS + "NotificationMapper.findPage"));
        assertTrue(interceptor.requiresResolvedContext(NS + "AuditLogMapper.findRecent"));
        assertTrue(interceptor.requiresResolvedContext(NS + "DataSubjectDisclosureMapper.findPerson"));
    }

    @Test
    void controlPlaneAndExemptStatementsDoNot() {
        assertFalse(interceptor.requiresResolvedContext(NS + "WorkspaceMapper.getRole"));
        assertFalse(interceptor.requiresResolvedContext(NS + "UserMapper.search"));
        assertFalse(interceptor.requiresResolvedContext(NS + "PreferenceMapper.isEnabled"));
        assertFalse(interceptor.requiresResolvedContext(NS + "NotificationQuietHoursMapper.findByUserId"));
        assertFalse(interceptor.requiresResolvedContext(NS + "AuditLogMapper.insert"));
        assertFalse(interceptor.requiresResolvedContext(NS + "AuditLogMapper.findOrgExport"));
        assertFalse(interceptor.requiresResolvedContext(NS + "NotificationMapper.lockRecipientMemberships"));
        assertFalse(interceptor.requiresResolvedContext(NS + "NotificationMapper.findRecipientIdsByActor"));
        assertFalse(interceptor.requiresResolvedContext(NS + "NotificationMapper.lockRecipientIdsByActor"));
        assertFalse(interceptor.requiresResolvedContext(NS + "CompanyMapper.clearOwnershipAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "PersonMapper.clearOwnershipAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "ReportMapper.clearDefinitionCreatorsAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "ReportMapper.clearSnapshotGeneratorsAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "CampaignMapper.clearCampaignUserReferencesAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "CampaignMapper.clearSnapshotCreatorsAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "ConsentMapper.clearEventCreatorsAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "WorkflowMapper.findAffectedByUserAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(
            NS + "WorkflowVersionMapper.findLockCandidatesByUserAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "RuleMapper.findLockCandidatesByUserAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "SuppressionMapper.clearCreatorsAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "SavedViewMapper.deleteForFreshMembership"));
        assertFalse(interceptor.requiresResolvedContext(
            NS + "SavedViewPreferenceMapper.deletePinsForFreshMembership"));
        assertFalse(interceptor.requiresResolvedContext(
            NS + "SavedViewPreferenceMapper.deleteDefaultsForFreshMembership"));
        assertFalse(interceptor.requiresResolvedContext(NS + "SavedViewPreferenceMapper.deletePinsForUserAnywhere"));
        assertFalse(interceptor.requiresResolvedContext(NS + "SavedViewPreferenceMapper.deleteDefaultsForUserAnywhere"));
        assertTrue(interceptor.requiresResolvedContext(NS + "CompanyMapper.clearMemberOwnership"));
        assertTrue(interceptor.requiresResolvedContext(NS + "PersonMapper.clearMemberOwnership"));
    }

    @Test
    void blocksScopedStatementOnRequestThreadWhenUnresolved() {
        bindRequest();
        assertThrows(ForbiddenException.class,
            () -> interceptor.enforce(NS + "CompanyMapper.getAllCompanies"));
    }

    @Test
    void allowsScopedStatementWhenResolved() {
        bindRequest();
        tenantContext.set(1, 1, 1, "owner", null);
        assertDoesNotThrow(() -> interceptor.enforce(NS + "CompanyMapper.getAllCompanies"));
    }

    @Test
    void allowsScopedStatementOffRequestThread() {
        assertDoesNotThrow(() -> interceptor.enforce(NS + "NotificationMapper.findTaskReminderCandidates"));
    }

    @Test
    void allowsExemptAuditInsertWhenUnresolved() {
        bindRequest();
        assertDoesNotThrow(() -> interceptor.enforce(NS + "AuditLogMapper.insert"));
    }

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
}
