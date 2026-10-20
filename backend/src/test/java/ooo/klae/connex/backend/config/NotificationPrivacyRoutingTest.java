package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWorkspacePrivacy;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.TestTransactionManager;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Exercises real bean wiring while an inbox read retains a dedicated tenant transaction. */
class NotificationPrivacyRoutingTest {
    @Test
    void authorityReadsSuspendBeforeControlRoutingAndRestoreTheOuterTransaction() throws Exception {
        var context = new TenantContext();
        context.set(7, 3, 11, "owner", "cnx_tenant_privacy");
        var transactions = new TestTransactionManager();
        var authority = mock(AiAssistantPrivacyService.class);
        when(authority.notificationWorkspaceGenerations(11, null)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(context.getCatalog());
            return List.of(new AiAssistantWorkspacePrivacy(7, 3, 1));
        });
        var scope = new TenantWorkScope(context, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        var beans = new StaticListableBeanFactory(Map.of("authority", authority, "scope", scope, "transactions", transactions));
        var interceptor = new MyBatisConfig().notificationPrivacyInterceptor(
                beans.getBeanProvider(AiAssistantPrivacyService.class), beans.getBeanProvider(TenantWorkScope.class),
                beans.getBeanProvider(PlatformTransactionManager.class));
        var statement = new MappedStatement.Builder(new Configuration(),
                "ooo.klae.connex.backend.mappers.NotificationMapper.findPage",
                new StaticSqlSource(new Configuration(), "SELECT 1"), SqlCommandType.SELECT).build();
        Executor executor = mock(Executor.class);
        when(executor.query(eq(statement), any(), any(), any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("cnx_tenant_privacy", context.getCatalog());
            return List.of();
        });
        var method = Executor.class.getMethod("query", MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                try {
                    interceptor.intercept(new Invocation(executor, method,
                            new Object[] {statement, Map.of("recipientId", 11), RowBounds.DEFAULT, null}));
                } catch (Throwable failure) {
                    throw new AssertionError(failure);
                }
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("cnx_tenant_privacy", context.getCatalog());
            });
            verify(authority, times(2)).notificationWorkspaceGenerations(11, null);
            assertEquals(2, transactions.suspendCount());
        } finally {
            context.clear();
        }
    }
}
