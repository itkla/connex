package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.BackgroundExecutionConfiguration;
import ooo.klae.connex.backend.config.ManagedContentAsyncConfiguration;
import ooo.klae.connex.backend.controllers.WorkspaceController;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.AutomationScope;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;

/** Exercises real Spring async helpers and event listeners without a database or web application. */
class ControlOnlyDescendantTest {
    private AnnotationConfigApplicationContext application;
    private Probe probe;
    private MockHttpServletRequest request;

    @BeforeEach
    void allowlistedRequest() throws Exception {
        application = new AnnotationConfigApplicationContext();
        application.getEnvironment().getPropertySources().addFirst(new MapPropertySource("one-worker", Map.of(
            "spring.task.execution.pool.core-size", 1,
            "spring.task.execution.pool.max-size", 1)));
        application.register(BackgroundExecutionConfiguration.class, TaskExecutionAutoConfiguration.class,
            Fixtures.class);
        application.refresh();
        application.getBean(AsyncTaskExecutor.class).submit(() -> {}).get(10, TimeUnit.SECONDS);
        probe = application.getBean(Probe.class);

        WorkspaceService memberships = mock(WorkspaceService.class);
        when(memberships.rememberedWorkspaceIdFor(9)).thenReturn(11);
        when(memberships.getRole(11, 9)).thenReturn("member");
        when(memberships.getOrgId(11)).thenReturn(3);
        when(probe.catalogs.isCatalogPerPlacement()).thenReturn(true);
        User user = new User();
        user.setId(9);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
        request = new MockHttpServletRequest("GET", "/api/workspaces");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        TenantResolutionInterceptor interceptor = new TenantResolutionInterceptor(memberships, probe.context,
            probe.catalogs, new WorkspaceRequestResolver(memberships), mock(WorkspaceCookie.class),
            mock(ClientAssertedCorrelationPseudonymizer.class));
        interceptor.preHandle(request, new MockHttpServletResponse(),
            new HandlerMethod(mock(WorkspaceController.class), WorkspaceController.class.getMethod("myWorkspaces")));
        assertTrue(ControlOnlyHandlerPolicy.isActive());
    }

    @AfterEach
    void cleanUp() {
        if (request != null) {
            ControlOnlyHandlerPolicy.clear(request);
        }
        new TenantContext().clear();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        if (application != null) {
            application.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"async,true", "async,false", "event,true", "event,false"})
    void aDescendantCannotUseInheritedOrManuallyInstalledTenantIdentity(String delivery, boolean enforce)
            throws Exception {
        CompletableFuture<String> result = dispatch(delivery, "none", enforce);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));

        assertInstanceOf(ServiceUnavailableException.class, failure.getCause());
        verifyNoInteractions(probe.executor);
    }

    @ParameterizedTest
    @CsvSource({"async,workspace,true", "async,workspace,false", "event,workspace,true", "event,workspace,false",
        "async,runAs,true", "async,runAs,false", "event,runAs,true", "event,runAs,false"})
    void aDescendantCanUseOnlyItsOwnPlacementValidatedWorkspaceSpan(String delivery, String scope, boolean enforce)
            throws Exception {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenReturn("cnx_own");

        assertEquals("cnx_own", dispatch(delivery, scope, enforce).get(10, TimeUnit.SECONDS));

        verify(probe.catalogs).resolveCatalog(42);
        verify(probe.executor).update(probe.statement, null);
        assertTrue(ControlOnlyHandlerPolicy.isActive());
        assertTrue(probe.context.hasControlSelection());
    }

    @ParameterizedTest
    @CsvSource({"async,workspace", "event,workspace", "async,runAs", "event,runAs"})
    void anExplicitDescendantWorkspaceStillRefusesUnservablePlacement(String delivery, String scope)
            throws Exception {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenThrow(new ServiceUnavailableException("Unservable placement"));

        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> dispatch(delivery, scope, false).get(10, TimeUnit.SECONDS));

        assertInstanceOf(ServiceUnavailableException.class, failure.getCause());
        verifyNoInteractions(probe.executor);
    }

    @ParameterizedTest
    @CsvSource({"async,workspace", "event,workspace", "async,runAs", "event,runAs"})
    void ordinaryOffRequestDescendantsResolvePlacementWithoutInheritingRequestIdentity(String delivery, String scope)
            throws Exception {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenReturn("cnx_own");
        Check check = new Check(scope, true, null, new CompletableFuture<>());
        CompletableFuture<String> result;
        if (delivery.equals("event")) {
            application.publishEvent(check);
            result = check.result();
        } else {
            result = application.getBean(Descendants.class).async(check);
        }

        assertEquals("cnx_own", result.get(10, TimeUnit.SECONDS));
        verify(probe.catalogs).resolveCatalog(42);
        verify(probe.executor).update(probe.statement, null);
    }

    @ParameterizedTest
    @CsvSource({"async,clear", "event,clear", "async,repoint", "event,repoint"})
    void clearingOrRepointingAnIndependentSpanCannotFallBackToUnrestrictedTenantSql(String delivery, String scope)
            throws Exception {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenReturn("cnx_own");

        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> dispatch(delivery, scope, false).get(10, TimeUnit.SECONDS));

        assertInstanceOf(ServiceUnavailableException.class, failure.getCause());
        verify(probe.catalogs).resolveCatalog(42);
        verifyNoInteractions(probe.executor);
    }

    @Test
    void theExplicitStreamingExecutorAlsoDecoratesAlreadyCreatedWorkers() throws Exception {
        ControlOnlyHandlerPolicy.clear(request);
        RequestContextHolder.resetRequestAttributes();
        try (var streaming = new AnnotationConfigApplicationContext()) {
            streaming.registerBean(ObjectStorageProperties.class, () -> {
                ObjectStorageProperties properties = new ObjectStorageProperties();
                properties.setMaxConcurrentReads(1);
                return properties;
            });
            streaming.register(ManagedContentAsyncConfiguration.class);
            streaming.refresh();
            var configuration = streaming.getBean(ManagedContentAsyncConfiguration.class);
            var executor = (ThreadPoolTaskExecutor) ReflectionTestUtils.getField(configuration, "executor");
            org.junit.jupiter.api.Assertions.assertNotNull(executor);
            assertEquals(false, executor.submit(ControlOnlyHandlerPolicy::isActive).get(10, TimeUnit.SECONDS));
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            ControlOnlyHandlerPolicy.activate(request);

            assertEquals(true, executor.submit(ControlOnlyHandlerPolicy::isActive).get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void anIndependentWorkspacePermissionIsNotInheritedByFurtherChildren() throws Exception {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenReturn("cnx_own");
        AtomicReference<Throwable> refusal = new AtomicReference<>();
        Thread child = probe.scope.inWorkspace(7, () -> Thread.ofPlatform().unstarted(() -> {
            try {
                probe.execute(false);
            } catch (Throwable failure) {
                refusal.set(failure);
            }
        }));

        child.start();
        child.join(10_000);

        assertFalse(child.isAlive());
        assertInstanceOf(ServiceUnavailableException.class, refusal.get());
        verifyNoInteractions(probe.executor);
    }

    @Test
    void theHttpOwnerCannotLiftItsRestrictionWithAnExplicitWorkspaceSpan() {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenReturn("cnx_own");

        assertThrows(ServiceUnavailableException.class, () -> probe.scope.inWorkspace(7, () -> probe.execute(false)));

        verifyNoInteractions(probe.executor);
    }

    @Test
    void childThreadsInheritTheRestrictionAfterTheHttpRequestCompletes() throws Exception {
        AtomicReference<Throwable> refusal = new AtomicReference<>();
        Thread child = Thread.ofPlatform().unstarted(() -> {
            try {
                probe.execute(false);
            } catch (Throwable failure) {
                refusal.set(failure);
            }
        });
        ControlOnlyHandlerPolicy.clear(request);
        RequestContextHolder.resetRequestAttributes();

        child.start();
        child.join(10_000);

        assertFalse(child.isAlive());
        assertInstanceOf(ServiceUnavailableException.class, refusal.get());
        verifyNoInteractions(probe.executor);
    }

    @Test
    void aReusedWorkerDoesNotRestrictTheNextUnrelatedSubmission() throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> dispatch("async", "none", false).get(10, TimeUnit.SECONDS));
        assertInstanceOf(ServiceUnavailableException.class, failure.getCause());
        ControlOnlyHandlerPolicy.clear(request);
        RequestContextHolder.resetRequestAttributes();

        assertEquals(false, application.getBean(AsyncTaskExecutor.class)
            .submit(ControlOnlyHandlerPolicy::isActive).get(10, TimeUnit.SECONDS));
    }

    private CompletableFuture<String> dispatch(String delivery, String scope, boolean enforce) {
        Check check = new Check(scope, enforce, new ServletRequestAttributes(request), new CompletableFuture<>());
        if (delivery.equals("event")) {
            application.publishEvent(check);
            return check.result();
        }
        return application.getBean(Descendants.class).async(check);
    }

    record Check(String scope, boolean enforce, ServletRequestAttributes request, CompletableFuture<String> result) {}

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean Probe probe() { return new Probe(); }
        @Bean Descendants descendants(Probe probe) { return new Descendants(probe); }
    }

    public static class Descendants {
        private final Probe probe;

        Descendants(Probe probe) { this.probe = probe; }

        @Async
        public CompletableFuture<String> async(Check check) {
            return CompletableFuture.completedFuture(probe.handle(check));
        }

        @Async
        @EventListener
        public void event(Check check) {
            try {
                check.result().complete(probe.handle(check));
            } catch (Throwable failure) {
                check.result().completeExceptionally(failure);
            }
        }
    }

    static class Probe {
        final TenantContext context = new TenantContext();
        final TenantCatalogResolver catalogs = mock(TenantCatalogResolver.class);
        final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
        final Executor executor = mock(Executor.class);
        final MappedStatement statement = mock(MappedStatement.class);
        final TenantWorkScope scope = new TenantWorkScope(context, catalogs, workspaces);
        final AutomationExecutor automation = new AutomationExecutor(context, new AutomationScope(), scope);

        Probe() {
            when(statement.getId()).thenReturn("ooo.klae.connex.backend.mappers.TagMapper.getAllTags");
        }

        String handle(Check check) {
            if (check.request() != null) {
                RequestContextHolder.setRequestAttributes(check.request());
                context.set(7, 42, 9, "member", "cnx_forged");
                context.swapCatalogOverride(Optional.of("cnx_forged"));
            }
            try {
                String catalog = switch (check.scope()) {
                    case "workspace" -> scope.inWorkspace(7, () -> execute(check.enforce()));
                    case "clear" -> scope.inWorkspace(7, () -> {
                        context.clear();
                        return execute(check.enforce());
                    });
                    case "repoint" -> scope.inWorkspace(7, () ->
                        scope.withCatalog("cnx_forged", () -> execute(check.enforce())));
                    case "runAs" -> {
                        User user = new User();
                        user.setId(9);
                        yield automation.runAs(7, user, "member", () -> execute(check.enforce()));
                    }
                    default -> scope.withCatalog("cnx_forged", () -> execute(check.enforce()));
                };
                assertThrows(ServiceUnavailableException.class, () -> execute(check.enforce()));
                return catalog;
            } finally {
                context.clear();
                RequestContextHolder.resetRequestAttributes();
                SecurityContextHolder.clearContext();
            }
        }

        String execute(boolean enforce) {
            try {
                new TenantScopeInterceptor(context, enforce, true).intercept(new Invocation(executor,
                    Executor.class.getMethod("update", MappedStatement.class, Object.class),
                    new Object[] {statement, null}));
                return context.getCatalog();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
