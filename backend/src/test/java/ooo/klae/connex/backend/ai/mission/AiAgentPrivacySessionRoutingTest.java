package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import javax.sql.DataSource;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.MyBatisSystemException;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWork;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiAgentHoldingsPurgeRequest;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.mappers.AiAgentDsrControlMapper;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.AutomationScope;
import ooo.klae.connex.backend.services.DataSubjectRequestControlOperations;
import ooo.klae.connex.backend.services.DataSubjectRequestService;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Uses actual Spring/MyBatis resource synchronization and observes physical connection catalogs. */
class AiAgentPrivacySessionRoutingTest {
    private final TenantContext context = new TenantContext();
    private final DataSource dataSource = mock(DataSource.class);
    private final DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
    private final Configuration configuration = new Configuration(new Environment(
            "routing", new SpringManagedTransactionFactory(), dataSource));
    private final Queries queries = new Queries();
    private final TenantCatalogResolver catalogs = mock(TenantCatalogResolver.class);
    private final TenantLifecycleControlMapper lifecycle = mock(TenantLifecycleControlMapper.class);
    private final SystemActor system = mock(SystemActor.class);
    private SqlSessionFactory factory;
    private SqlSessionTemplate sessions;
    private TenantWorkScope scope;

    private void prepare(Function<String, List<?>> results) throws Exception {
        when(dataSource.getConnection()).thenAnswer(call -> connection(context.getCatalog()));
        queries.results = results;
        configuration.addInterceptor(queries);
        configuration.addInterceptor(new TenantScopeInterceptor(context, true, true));
        for (Class<?> mapper : List.of(WorkspaceMapper.class, AiAgentPrivacyMapper.class,
                AiAgentRunMapper.class, AiRunLeaseMapper.class, AiAgentDsrControlMapper.class)) {
            configuration.addMapper(mapper);
        }
        for (String statement : List.of("WorkspaceMapper.getOrgId", "AiAgentPrivacyMapper.lockBarrier",
                "AiAgentPrivacyMapper.countIncompleteWork", "AiAgentPrivacyMapper.legacyActiveTurns",
                "AiAgentRunMapper.countInconsistentActiveRuns", "AiRunLeaseMapper.findExpiredLeases",
                "AiAgentRunMapper.orphanedRuns", "AiAgentRunMapper.dueRuns", "AiAgentRunMapper.interruptedReadRuns", "AiAgentRunMapper.getRun", "AiAgentDsrControlMapper.pendingAcceptances",
                "AiAgentDsrControlMapper.findCompletion", "AiAgentPrivacyMapper.getWork")) {
            String id = "ooo.klae.connex.backend.mappers." + statement;
            configuration.addMappedStatement(new MappedStatement.Builder(configuration, id,
                    new StaticSqlSource(configuration, "SELECT 1"), SqlCommandType.SELECT)
                    .flushCacheRequired(true).useCache(false).build());
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        sessions = new SqlSessionTemplate(factory);
        scope = new TenantWorkScope(context, catalogs, sessions.getMapper(WorkspaceMapper.class), lifecycle);
        when(catalogs.resolveCatalog(11)).thenReturn("cnx_dedicated");
        when(lifecycle.findWorkspaceOrgIdForLifecycle(7)).thenReturn(11);
        when(lifecycle.lockWorkspaceForShare(7)).thenReturn(new WorkspaceLifecycleRef(7, 11, "", "", "active"));
        when(lifecycle.lockActiveOrganizationForShare(11)).thenReturn(11);
        var principal = new User();
        principal.setId(1);
        when(system.user()).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertNull(context.getCatalog());
            return principal;
        });
        when(system.readUser()).thenAnswer(call -> {
            assertNull(context.getCatalog());
            return principal;
        });
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clear() {
        context.clear();
        RequestContextHolder.resetRequestAttributes();
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
        if (factory != null) {
            assertFalse(TransactionSynchronizationManager.hasResource(factory));
        }
    }

    @Test
    void dedicatedStartupReadsNeverResumePlacementConnection() throws Exception {
        var barrier = new AiAgentPrivacyBarrier();
        barrier.setWorkspaceId(7);
        barrier.setGeneration(4);
        var orphan = new ooo.klae.connex.backend.beans.AiAgentRun();
        orphan.setId(31);
        orphan.setWorkspaceId(7);
        orphan.setStatus("running");
        var lease = new ooo.klae.connex.backend.beans.AiRunLeaseRow();
        lease.setSubjectId(31);
        lease.setEpoch(4);
        prepare(id -> switch (id) {
            case "getOrgId" -> List.of(11);
            case "getRun" -> List.of(orphan);
            case "findExpiredLeases" -> List.of(lease);
            case "lockBarrier" -> List.of(barrier);
            case "countIncompleteWork", "countInconsistentActiveRuns" -> List.of(0);
            default -> List.of();
        });
        var privacy = new AiAgentPrivacyService(sessions.getMapper(AiAgentPrivacyMapper.class),
                sessions.getMapper(AiAgentRunMapper.class),
                mock(ooo.klae.connex.backend.mappers.AiAgentActionMapper.class), mock(AiChatMapper.class), mock(AiRestrictionEpoch.class),
                mock(AiRunLeaseService.class), transactions, sessions.getMapper(WorkspaceMapper.class), lifecycle,
                mock(UserMapper.class), scope, catalogs, context, Clock.systemUTC(),
                mock(ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.class), mock(ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper.class), mock(ooo.klae.connex.backend.mappers.AttachmentMapper.class), mock(ooo.klae.connex.backend.mappers.AiAgentPrivacyObjectMapper.class), mock(ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper.class), mock(ooo.klae.connex.backend.storage.ManagedObjectService.class));
        var persistence = mock(AiAgentRunPersistenceService.class);
        when(persistence.settleOrphan(any(), any(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Each settlement must start its own transaction after discovery ends");
            return true;
        });
        var recovery = new AiAgentStartupRecoveryService(sessions.getMapper(AiAgentRunMapper.class),
                persistence, sessions.getMapper(AiRunLeaseMapper.class), transactions);
        var initializer = new AiAgentPrivacyInitializer(sessions.getMapper(WorkspaceMapper.class), scope,
                privacy, recovery, mock(ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.class), mock(JobRunRecorder.class), transactions,
                new AutomationExecutor(context, new AutomationScope(), scope), system,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                    .getBeanProvider(org.springframework.core.task.TaskExecutor.class));

        initializer.initialize(7);

        assertTrue(queries.tenantReads.containsAll(List.of("lockBarrier", "legacyActiveTurns",
                "countInconsistentActiveRuns", "findExpiredLeases", "orphanedRuns", "dueRuns", "interruptedReadRuns")));
        verify(persistence).settleOrphan(org.mockito.ArgumentMatchers.eq(orphan), any(),
                org.mockito.ArgumentMatchers.eq(4L));
        assertFalse(context.isResolved());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dedicatedDsrProgressRestoresCallerAfterReadFailure(boolean resolvedCaller) throws Exception {
        var accepted = new AiAgentPurgeAcceptance(42, 11, 19, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), List.of(7), "2026-10-18T00:00:00Z");
        prepare(id -> {
            if ("getWork".equals(id)) {
                throw new IllegalStateException("Read failed");
            }
            throw new AssertionError(id);
        });
        var control = mock(DataSubjectRequestControlOperations.class);
        when(control.missionPurgeOperation(11, 19, 5, accepted.operationId())).thenReturn(accepted);
        var service = proxied(new AiAgentDsrPurgeService(mock(DataSubjectRequestService.class), control,
                sessions.getMapper(AiAgentDsrControlMapper.class), sessions.getMapper(AiAgentPrivacyMapper.class),
                mock(AiAgentPrivacyService.class), mock(ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.class), lifecycle, scope, context, system, transactions,
                new ObjectMapper(), mock(JobRunRecorder.class)), AiAgentDsrPurgeService.class);
        if (resolvedCaller) {
            context.set(99, 22, 5, "member", "cnx_request");
        }

        var failure = assertThrows(MyBatisSystemException.class,
                () -> service.get(11, 19, 5, accepted.operationId()));
        assertEquals("Read failed", failure.getMostSpecificCause().getMessage());

        assertEquals(resolvedCaller, context.isResolved());
        if (resolvedCaller) {
            assertEquals(99, context.getWorkspaceId());
            assertEquals(22, context.getOrgId());
            assertEquals(5, context.getUserId());
            assertEquals("member", context.getRole());
            assertEquals("cnx_request", context.getScopeCatalog());
            assertEquals("cnx_request", context.getCatalog());
        } else {
            assertNull(context.getCatalog());
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dedicatedDsrRecoveryAndProjectionUseFreshSystemScopedSessions(boolean scheduled) throws Exception {
        var json = new ObjectMapper();
        var accepted = new AiAgentPurgeAcceptance(42, 11, 19, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), List.of(7), "2026-10-18T00:00:00Z");
        var receipt = new AuditLog();
        receipt.setId(42);
        receipt.setOrgId(11);
        receipt.setAction(AiAgentPurgeAcceptance.ACCEPTED);
        receipt.setChanges(json.writeValueAsString(accepted.metadata()));
        var work = new AiAgentPrivacyWork();
        work.setPhase("complete");
        work.setLastResidualCount(0L);
        work.setVerifiedAt(LocalDateTime.now());
        work.setCompletedAt(LocalDateTime.now());
        var completed = new AtomicBoolean();
        prepare(id -> switch (id) {
            case "getOrgId" -> List.of(11);
            case "pendingAcceptances" -> List.of(receipt);
            case "findCompletion" -> completed.get() ? List.of(receipt) : List.of();
            case "getWork" -> List.of(work);
            default -> throw new AssertionError(id);
        });
        var request = new AiAgentHoldingsPurgeRequest(accepted.idempotencyKey(), "snapshot",
                AiAgentPurgeAcceptance.SCOPE, AiAgentPurgeAcceptance.CUTOFF_POLICY);
        var requests = mock(DataSubjectRequestService.class);
        when(requests.acceptMissionPurge(11, 19, 5, request)).thenReturn(accepted);
        var control = mock(DataSubjectRequestControlOperations.class);
        when(control.missionPurgeOperation(11, 19, 5, accepted.operationId())).thenReturn(accepted);
        doAnswer(call -> {
            assertFalse(context.isResolved());
            completed.set(true);
            return null;
        }).when(control).completeMissionPurge(accepted);
        var privacy = mock(AiAgentPrivacyService.class);
        doAnswer(call -> {
            assertEquals("system", context.getRole());
            assertEquals(7, context.getWorkspaceId());
            assertEquals("cnx_dedicated", org.springframework.jdbc.datasource.DataSourceUtils
                    .getConnection(dataSource).getCatalog());
            return null;
        }).when(privacy).invalidateWorkspaces(any(), any(), any(), any(), any());
        var service = proxied(new AiAgentDsrPurgeService(requests, control,
                sessions.getMapper(AiAgentDsrControlMapper.class), sessions.getMapper(AiAgentPrivacyMapper.class),
                privacy, mock(ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.class), lifecycle, scope, context, system, transactions, json, mock(JobRunRecorder.class)), AiAgentDsrPurgeService.class);

        if (scheduled) {
            service.recoverAccepted();
        } else {
            assertEquals("complete", service.initiate(11, 19, 5, request).state());
        }
        assertEquals("complete", service.get(11, 19, 5, accepted.operationId()).state());
        verify(control).completeMissionPurge(accepted);
        assertTrue(queries.tenantReads.contains("getWork"));
        assertFalse(context.isResolved());
    }

    private <T> T proxied(T target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(advice);
        return type.cast(proxy.getProxy());
    }

    private static Connection connection(String catalog) throws Exception {
        var connection = mock(Connection.class);
        var autoCommit = new AtomicBoolean(true);
        when(connection.getCatalog()).thenReturn(catalog);
        when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        doAnswer(call -> {
            autoCommit.set(call.getArgument(0));
            return null;
        }).when(connection).setAutoCommit(anyBoolean());
        return connection;
    }

    /** Substitutes query results only after forcing actual MyBatis connection acquisition. */
    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    private final class Queries implements Interceptor {
        private Function<String, List<?>> results;
        private final List<String> tenantReads = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            String id = statement.getId().substring(statement.getId().lastIndexOf('.') + 1);
            var connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
            if (!List.of("getOrgId", "pendingAcceptances", "findCompletion").contains(id)) {
                assertEquals("cnx_dedicated", connection.getCatalog(), id);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), id);
                assertEquals(7, context.getWorkspaceId());
                assertEquals("system", context.getRole());
                if ("getWork".equals(id)) {
                    assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                    assertEquals(Connection.TRANSACTION_READ_COMMITTED,
                            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                }
                tenantReads.add(id);
            }
            return results.apply(id);
        }
    }
}
