package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.BackgroundExecutionConfiguration;
import ooo.klae.connex.backend.controllers.WorkspaceController;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.AutomationScope;
import ooo.klae.connex.backend.services.WorkspaceService;

/** Exercises ordinary scope enforcement in real async, event and raw-pool descendants without a database. */
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
        probe = application.getBean(Probe.class);
        startRequest();
    }

    private void startRequest() throws Exception {
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
            mock(ClientAssertedCorrelationPseudonymizer.class),
            org.mockito.Mockito.mock(ooo.klae.connex.backend.tenant.TenantWorkScope.class));
        interceptor.preHandle(request, new MockHttpServletResponse(),
            new HandlerMethod(mock(WorkspaceController.class), WorkspaceController.class.getMethod("myWorkspaces")));
        assertTrue(ControlOnlyHandlerPolicy.isActive());
        assertTrue(probe.context.hasControlSelection());
        assertFalse(probe.context.isResolved());
        assertFalse(probe.context.hasCatalogOverride());
    }

    @AfterEach
    void cleanUp() {
        endRequest();
        if (application != null) {
            application.close();
        }
    }

    private void endRequest() {
        if (request != null) {
            ControlOnlyHandlerPolicy.clear(request);
        }
        new TenantContext().clear();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"async,none", "event,none", "async,copied", "event,copied"})
    void ordinaryEnforcementRefusesUnscopedDescendantsIncludingACopiedControlSelection(String delivery, String scope)
            throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> dispatch(delivery, scope).get(10, TimeUnit.SECONDS));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("no catalog scope"));
        verifyNoInteractions(probe.executor);
    }

    @ParameterizedTest
    @CsvSource({"async,workspace", "event,workspace", "async,runAs", "event,runAs"})
    void aDescendantCanEnterItsOwnPlacementValidatedScope(String delivery, String scope) throws Exception {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenReturn("cnx_own");

        assertEquals("cnx_own", dispatch(delivery, scope).get(10, TimeUnit.SECONDS));

        verify(probe.catalogs).resolveCatalog(42);
        verify(probe.executor).update(probe.statement, null);
        assertTrue(ControlOnlyHandlerPolicy.isActive());
        assertTrue(probe.context.hasControlSelection());
        assertFalse(probe.context.isResolved());
    }

    @ParameterizedTest
    @CsvSource({"async,workspace", "event,workspace", "async,runAs", "event,runAs"})
    void anExplicitDescendantScopeStillRefusesUnservablePlacement(String delivery, String scope) throws Exception {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenThrow(new ServiceUnavailableException("Unservable placement"));

        ExecutionException failure = assertThrows(ExecutionException.class,
            () -> dispatch(delivery, scope).get(10, TimeUnit.SECONDS));

        assertInstanceOf(ServiceUnavailableException.class, failure.getCause());
        verify(probe.catalogs).resolveCatalog(42);
        verifyNoInteractions(probe.executor);
    }

    @ParameterizedTest
    @CsvSource({"raw,false", "raw,true", "spring,false", "spring,true"})
    void coldAndPrewarmedWorkersRunOrdinaryWorkAfterAControlOnlyRequest(String delivery, boolean prewarmed)
            throws Exception {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenReturn("cnx_own");
        try (var raw = Executors.newSingleThreadExecutor()) {
            Function<Callable<String>, Future<String>> submit = delivery.equals("raw")
                ? raw::submit : application.getBean(AsyncTaskExecutor.class)::submit;
            AtomicReference<Thread> worker = new AtomicReference<>();
            if (prewarmed) {
                endRequest();
                submit.apply(() -> {
                    assertFalse(ControlOnlyHandlerPolicy.isActive());
                    worker.set(Thread.currentThread());
                    return "warm";
                }).get(10, TimeUnit.SECONDS);
                startRequest();
            }
            Check check = check("none");
            ExecutionException failure = assertThrows(ExecutionException.class, () -> submit.apply(() -> {
                if (prewarmed) {
                    assertEquals(worker.get(), Thread.currentThread());
                } else {
                    worker.set(Thread.currentThread());
                }
                return probe.handle(check);
            }).get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            verifyNoInteractions(probe.executor);
            endRequest();

            assertEquals("cnx_own", submit.apply(() -> {
                assertEquals(worker.get(), Thread.currentThread());
                return probe.handle(new Check("workspace", null, new CompletableFuture<>()));
            }).get(10, TimeUnit.SECONDS));
            verify(probe.executor).update(probe.statement, null);
        }
    }

    @Test
    void aFurtherChildDoesNotInheritItsParentsExplicitWorkspaceScope() throws Exception {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenReturn("cnx_own");
        AtomicReference<Throwable> refusal = new AtomicReference<>();
        Thread child = probe.scope.inWorkspace(7, () -> Thread.ofPlatform().unstarted(() -> {
            try {
                probe.execute(true);
            } catch (Throwable failure) {
                refusal.set(failure);
            }
        }));
        child.start();
        child.join(10_000);

        assertFalse(child.isAlive());
        assertInstanceOf(IllegalStateException.class, refusal.get());
        verifyNoInteractions(probe.executor);
    }

    @Test
    void theHttpOwnerCannotLiftItsRequestRestrictionWithAnExplicitWorkspaceScope() {
        when(probe.workspaces.getOrgId(7)).thenReturn(42);
        when(probe.catalogs.resolveCatalog(42)).thenReturn("cnx_own");

        assertThrows(ServiceUnavailableException.class, () -> probe.scope.inWorkspace(7, () -> probe.execute(false)));

        verifyNoInteractions(probe.executor);
    }

    private CompletableFuture<String> dispatch(String delivery, String scope) {
        Check check = check(scope);
        if (delivery.equals("event")) {
            application.publishEvent(check);
            return check.result();
        }
        return application.getBean(Descendants.class).async(check);
    }

    private Check check(String scope) {
        Selection selection = new Selection(probe.context.getWorkspaceId(), probe.context.getOrgId(),
            probe.context.getUserId(), probe.context.getRole());
        return new Check(scope, selection, new CompletableFuture<>());
    }

    record Selection(int workspaceId, int orgId, int userId, String role) {}
    record Check(String scope, Selection selection, CompletableFuture<String> result) {}

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
            assertNull(RequestContextHolder.getRequestAttributes());
            assertFalse(ControlOnlyHandlerPolicy.isActive());
            assertFalse(context.isResolved());
            assertFalse(context.hasControlSelection());
            assertFalse(context.hasCatalogOverride());
            if (check.scope().equals("copied")) {
                Selection selection = check.selection();
                context.setControlSelection(selection.workspaceId(), selection.orgId(), selection.userId(),
                    selection.role());
                assertFalse(context.isResolved());
                assertNull(context.getCatalog());
                assertFalse(context.hasCatalogOverride());
            }
            try {
                String catalog = switch (check.scope()) {
                    case "workspace" -> scope.inWorkspace(7, () -> execute(true));
                    case "runAs" -> {
                        User user = new User();
                        user.setId(9);
                        yield automation.runAs(7, user, "member", () -> execute(true));
                    }
                    default -> execute(true);
                };
                assertThrows(IllegalStateException.class, () -> execute(true));
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
