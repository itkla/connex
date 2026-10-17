package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.DataSubjectDisclosureJsonWriter.Fingerprint;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Exercises the real transaction interceptor and catalog guard around the strict audit bridge. */
class DataSubjectDisclosureAuditAccessTest {
    private final TenantContext context = new TenantContext();
    private final AuditService audit = mock(AuditService.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final Fingerprint fingerprint = new Fingerprint(250, "a".repeat(64), 1024, "b".repeat(64));
    private DataSubjectDisclosureAuditAccess access;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var manager = new DataSourceTransactionManager(dataSource);
        var scope = new TenantWorkScope(context, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        ProxyFactory proxy = new ProxyFactory(new DataSubjectDisclosureAuditAccess(scope, audit));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        access = (DataSubjectDisclosureAuditAccess) proxy.getProxy();
        transaction = new TransactionTemplate(manager);
        context.set(4, 3, 7, "org_admin", "cnx_subject");
    }

    @AfterEach
    void clear() {
        context.clear();
    }

    @Test
    void suspendsBeforeSwitchingAndResumesTheSameResourceAfterScopedStrictAudit() {
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
            assertEquals(null, context.getCatalog());
            assertEquals(4, context.getWorkspaceId());
            Map<String, Object> changes = invocation.getArgument(7);
            assertEquals(1, changes.get("fingerprintVersion"));
            assertEquals(250L, changes.get("noteCount"));
            assertEquals(fingerprint.notesSha256(), changes.get("notesSha256"));
            assertEquals(fingerprint.byteLength(), changes.get("jsonByteLength"));
            assertEquals(fingerprint.sha256(), changes.get("jsonSha256"));
            assertEquals(9L, changes.get("requestId"));
            assertEquals(5, changes.get("subjectPersonId"));
            assertEquals(4, changes.get("subjectWorkspaceId"));
            return null;
        }).when(audit).recordStrictIndependentScoped(eq("appi.subject_request.disclosure"),
            eq("organization"), eq(3), isNull(), eq(3), eq("Subject request 9"),
            eq("Subject-scoped disclosure export assembled"), any());
        transaction.executeWithoutResult(status -> {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            access.record(3, 9, 5, 4, fingerprint);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(resource, TransactionSynchronizationManager.getResource(dataSource));
            assertEquals("cnx_subject", context.getCatalog());
            assertEquals("org_admin", context.getRole());
        });
    }

    @Test
    void strictFailurePropagatesAndRestoresCatalogAndSuspendedResources() {
        IllegalStateException failure = new IllegalStateException("strict audit unavailable");
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(null, context.getCatalog());
            throw failure;
        }).when(audit).recordStrictIndependentScoped(any(), any(), any(), isNull(), any(), any(), any(), any());
        transaction.executeWithoutResult(status -> {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            assertSame(failure, assertThrows(IllegalStateException.class,
                () -> access.record(3, 9, 5, 4, fingerprint)));
            assertSame(resource, TransactionSynchronizationManager.getResource(dataSource));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("cnx_subject", context.getCatalog());
        });
    }
}
