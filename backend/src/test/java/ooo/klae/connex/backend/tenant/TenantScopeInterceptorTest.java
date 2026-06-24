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
    private final TenantScopeInterceptor interceptor = new TenantScopeInterceptor(tenantContext, true);

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void scopedStatementsRequireResolvedContext() {
        assertTrue(interceptor.requiresResolvedContext(NS + "CompanyMapper.getAllCompanies"));
        assertTrue(interceptor.requiresResolvedContext(NS + "DealMapper.search"));
        assertTrue(interceptor.requiresResolvedContext(NS + "NotificationMapper.findPage"));
        assertTrue(interceptor.requiresResolvedContext(NS + "AuditLogMapper.findRecent"));
    }

    @Test
    void controlPlaneAndExemptStatementsDoNot() {
        assertFalse(interceptor.requiresResolvedContext(NS + "WorkspaceMapper.getRole"));
        assertFalse(interceptor.requiresResolvedContext(NS + "UserMapper.search"));
        assertFalse(interceptor.requiresResolvedContext(NS + "PreferenceMapper.isEnabled"));
        assertFalse(interceptor.requiresResolvedContext(NS + "AuditLogMapper.insert"));
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
        tenantContext.set(1, 1, "owner");
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
