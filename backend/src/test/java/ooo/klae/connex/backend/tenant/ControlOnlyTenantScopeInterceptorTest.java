package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;

class ControlOnlyTenantScopeInterceptorTest {
    private static final String MAPPERS = "ooo.klae.connex.backend.mappers.";
    private final TenantContext context = new TenantContext();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final Executor executor = mock(Executor.class);
    private final MappedStatement statement = mock(MappedStatement.class);

    @BeforeEach
    void bindControlRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ControlOnlyHandlerPolicy.activate(request);
    }

    @AfterEach
    void clearContext() {
        ControlOnlyHandlerPolicy.clear(request);
        context.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @MethodSource("executionMethods")
    void everyExecutorEntryRefusesTenantSqlBeforeExecutingIt(Method method) {
        when(statement.getId()).thenReturn(MAPPERS + "TagMapper.getAllTags");

        assertThrows(ServiceUnavailableException.class, () -> new TenantScopeInterceptor(context, true, true)
            .intercept(invocation(method)));

        verifyNoInteractions(executor);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void tenantExemptionsAndUnknownStatementsCannotBypassTheCapability(boolean enforce) throws Exception {
        TenantScopeInterceptor interceptor = new TenantScopeInterceptor(context, enforce, true);
        context.set(11, 3, 7, "owner", "cnx_tenant");
        context.swapCatalogOverride(Optional.of("cnx_other"));
        for (String id : new String[] {
                MAPPERS + "AiChatMapper.deleteParticipantsForUser",
                MAPPERS + "NotificationMapper.deleteAllForRecipient",
                MAPPERS + "UnknownMapper.read",
                "unknown.namespace.read"}) {
            when(statement.getId()).thenReturn(id);
            assertThrows(ServiceUnavailableException.class, () -> interceptor.intercept(updateInvocation()));
        }
        context.clear();
        assertThrows(ServiceUnavailableException.class, () -> interceptor.intercept(updateInvocation()));

        verifyNoInteractions(executor);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void physicalControlStatementsStillExecuteIncludingMixedMapperControlStatements(boolean enforce)
            throws Throwable {
        TenantScopeInterceptor interceptor = new TenantScopeInterceptor(context, enforce, true);
        for (String id : new String[] {
                MAPPERS + "WorkspaceMapper.setLastActiveWorkspaceId",
                MAPPERS + "UserMapper.update",
                MAPPERS + "AuditLogMapper.insert",
                MAPPERS + "NotificationMapper.bumpStateVersions"}) {
            when(statement.getId()).thenReturn(id);
            interceptor.intercept(updateInvocation());
        }

        verify(executor, org.mockito.Mockito.times(4)).update(statement, null);
    }

    @Test
    void aCallerSuppliedLookalikeAttributeIsNotACapability() {
        ControlOnlyHandlerPolicy.clear(request);
        request.setAttribute(ControlOnlyHandlerPolicy.class.getName() + ".CAPABILITY", new Object());

        assertFalse(ControlOnlyHandlerPolicy.isActive());
    }

    private Invocation updateInvocation() throws NoSuchMethodException {
        return invocation(Executor.class.getMethod("update", MappedStatement.class, Object.class));
    }

    private Invocation invocation(Method method) {
        Object[] args = new Object[method.getParameterCount()];
        args[0] = statement;
        for (int index = 1; index < args.length; index++) {
            Class<?> type = method.getParameterTypes()[index];
            if (type == RowBounds.class) {
                args[index] = RowBounds.DEFAULT;
            } else if (type == CacheKey.class) {
                args[index] = new CacheKey();
            } else if (type == BoundSql.class) {
                args[index] = mock(BoundSql.class);
            }
        }
        return new Invocation(executor, method, args);
    }

    static Stream<Method> executionMethods() {
        return Arrays.stream(Executor.class.getMethods())
            .filter(method -> method.getName().equals("update") || method.getName().equals("query")
                || method.getName().equals("queryCursor"));
    }
}
