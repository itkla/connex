package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.exceptions.ForbiddenException;

class TenantScopeInterceptorTest {

    private static final String NS = "ooo.klae.connex.backend.mappers.";

    private static final Set<String> NON_EXECUTING_EXECUTOR_METHODS =
        Set.of("createCacheKey", "isCached", "deferLoad");

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
            NS + "IdentityCollisionMapper.findVisibleGroupPage"));
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

    /**
     * Every statement {@code UserOffboardingService.prepareFreshMembership} reaches must run
     * without a resolved context, because a first-time invitee and every SSO JIT provisioning
     * arrive on a request thread with no workspace to resolve. The provider-capture purge was
     * added to that flow after its exempt set was curated, so only the {@code *Anywhere} variants
     * were listed and the workspace-scoped ones it actually calls threw (#1011). The assistant-chat
     * cleanup repeated that mistake because this guard hardcoded a provider-capture prefix and so
     * could not observe a new mapper joining the flow.
     *
     * <p>The list below is every scoped statement the flow reaches, not a sample. It is still
     * hand-maintained rather than derived from the service call graph, so a newly added cleanup can
     * drift out of it; coupling this guard structurally to that call graph is tracked separately.
     */
    @Test
    void freshMembershipScopedCleanupRunsWithoutAResolvedContext() {
        bindRequest();
        for (String id : new String[] {
            NS + "ProviderCaptureMapper.deleteProviderActivities",
            NS + "ProviderCaptureMapper.deleteInteractions",
            NS + "ProviderCaptureMapper.deleteSyncStates",
            NS + "ProviderCaptureMapper.deleteUserPolicy",
            NS + "ProviderCaptureMapper.deleteDecisions",
            NS + "ProviderCaptureMapper.countUserProviderResiduals",
            NS + "ProviderCaptureMapper.clearWorkspacePolicyUpdater",
            NS + "AiChatMapper.deleteParticipantsForUser",
            NS + "AiChatMapper.deleteOwnedSessionsForUser",
            NS + "SavedViewPreferenceMapper.deletePinsForFreshMembership",
            NS + "SavedViewPreferenceMapper.deleteDefaultsForFreshMembership",
            NS + "SavedViewMapper.deleteForFreshMembership",
            NS + "NotificationMapper.deleteHistoricalNotificationBaselinesForRecipient",
            NS + "NotificationMapper.deleteAllForRecipient",
            NS + "DealMapper.removeCollaboratorFromWorkspace",
        }) {
            assertFalse(interceptor.requiresResolvedContext(id), id);
            assertDoesNotThrow(() -> interceptor.enforce(id), id);
        }
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

    @Test
    void bothTenancyPluginsInterceptEveryStatementExecutingExecutorMethod() {
        for (String name : NON_EXECUTING_EXECUTOR_METHODS) {
            assertTrue(
                Arrays.stream(Executor.class.getDeclaredMethods())
                    .anyMatch(method -> method.getName().equals(name)),
                "the non-executing exclusion " + name + " no longer exists on Executor");
        }
        assertInterceptsEveryStatementExecutingExecutorMethod(TenantScopeInterceptor.class);
        assertInterceptsEveryStatementExecutingExecutorMethod(ControlCatalogRoutingInterceptor.class);
    }

    @Test
    void pluginWiringBlocksEveryStatementExecutingMethodWhenUnresolved() throws Exception {
        bindRequest();
        Executor delegate = mock(Executor.class);
        MappedStatement statement = mock(MappedStatement.class);
        when(statement.getId()).thenReturn(NS + "TenantLifecycleMapper.streamRows");
        Executor wrapped = (Executor) interceptor.plugin(delegate);
        Object parameter = new Object();

        assertThrows(ForbiddenException.class,
            () -> wrapped.update(statement, parameter));
        assertThrows(ForbiddenException.class,
            () -> wrapped.query(statement, parameter, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER));
        assertThrows(ForbiddenException.class,
            () -> wrapped.query(statement, parameter, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER,
                new CacheKey(), mock(BoundSql.class)));
        assertThrows(ForbiddenException.class,
            () -> wrapped.queryCursor(statement, parameter, RowBounds.DEFAULT));
        verifyNoInteractions(delegate);
    }

    @Test
    void pluginWiringLetsAResolvedCursorStatementReachTheExecutor() throws Exception {
        bindRequest();
        tenantContext.set(1, 1, 1, "org_admin", null);
        Executor delegate = mock(Executor.class);
        MappedStatement statement = mock(MappedStatement.class);
        when(statement.getId()).thenReturn(NS + "TenantLifecycleMapper.streamRows");
        Executor wrapped = (Executor) interceptor.plugin(delegate);
        Object parameter = new Object();

        assertDoesNotThrow(() -> wrapped.queryCursor(statement, parameter, RowBounds.DEFAULT));

        verify(delegate).queryCursor(statement, parameter, RowBounds.DEFAULT);
    }

    /**
     * Asserts that an interceptor's {@code @Intercepts} set is exactly the
     * {@link Executor} methods that reach the database. Every method taking a
     * {@link MappedStatement} executes it except the three in
     * {@code NON_EXECUTING_EXECUTOR_METHODS} — cache-key derivation, a
     * local-cache probe, and nested-select deferral — so only those may be
     * excluded, and widening that list means exempting a real statement path
     * from the backstop.
     *
     * @param interceptorType the annotated MyBatis plugin under test
     */
    private static void assertInterceptsEveryStatementExecutingExecutorMethod(Class<?> interceptorType) {
        Set<String> executing = new TreeSet<>();
        for (Method method : Executor.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length > 0
                    && parameters[0] == MappedStatement.class
                    && !NON_EXECUTING_EXECUTOR_METHODS.contains(method.getName())) {
                executing.add(signatureKey(method.getName(), parameters));
            }
        }
        Set<String> intercepted = new TreeSet<>();
        for (Signature signature : interceptorType.getAnnotation(Intercepts.class).value()) {
            if (signature.type() == Executor.class) {
                intercepted.add(signatureKey(signature.method(), signature.args()));
            }
        }
        assertEquals(executing, intercepted,
            interceptorType.getSimpleName()
                + " must intercept exactly the statement-executing Executor methods; a MyBatis "
                + "upgrade that adds or removes one needs an explicit decision here");
    }

    private static String signatureKey(String method, Class<?>[] parameters) {
        return Arrays.stream(parameters)
            .map(Class::getName)
            .collect(Collectors.joining(",", method + "(", ")"));
    }

    private void bindRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
}
