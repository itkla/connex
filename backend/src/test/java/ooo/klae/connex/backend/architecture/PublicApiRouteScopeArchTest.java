package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.config.PublicApiSecurityConfig;
import ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter.RouteTransactionMode;

class PublicApiRouteScopeArchTest {
    private static final String CONTROLLER_PACKAGE =
        "ooo.klae.connex.backend.controllers";

    @Test
    void everyPublicControllerMappingHasOneExplicitScopeRule() throws Exception {
        Set<Route> controllerRoutes = controllerRoutes();
        Set<Route> configuredRoutes = new HashSet<>();
        for (PublicApiSecurityConfig.RouteRule rule : PublicApiSecurityConfig.routeRules()) {
            assertFalse(rule.authority().isBlank());
            Route route = new Route(rule.method(), rule.path());
            assertTrue(configuredRoutes.add(route), () -> "Duplicate public route rule: " + route);
            RouteTransactionMode expectedMode = HttpMethod.GET.equals(rule.method())
                ? RouteTransactionMode.READ
                : RouteTransactionMode.WRITE;
            assertEquals(expectedMode, rule.transactionMode(),
                () -> "Public route has the wrong transaction mode: " + route);
            Set<HttpMethod> expectedAuthorizationMethods =
                RouteTransactionMode.READ.equals(expectedMode)
                    ? Set.of(HttpMethod.GET, HttpMethod.HEAD)
                    : Set.of(rule.method());
            assertEquals(expectedAuthorizationMethods, rule.authorizationMethods(),
                () -> "Public route has the wrong authorization methods: " + route);
        }

        assertEquals(controllerRoutes, configuredRoutes);
    }

    @Test
    void publicSecurityChainIsAbsentUnlessExplicitlyEnabled() {
        ConditionalOnProperty conditional =
            PublicApiSecurityConfig.class.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(conditional);
        assertEquals(Set.of("connex.public-api.enabled"), Set.of(conditional.name()));
        assertEquals("true", conditional.havingValue());
    }

    @Test
    void routeRegistryDrivesTheRuntimeTransactionMode() {
        assertEquals(RouteTransactionMode.READ, transactionMode("GET", "/api/v1/me"));
        assertEquals(RouteTransactionMode.READ, transactionMode("HEAD", "/api/v1/me"));
        assertEquals(RouteTransactionMode.READ, transactionMode("OPTIONS", "/api/v1/me"));
        assertEquals(RouteTransactionMode.WRITE, transactionMode("POST", "/api/v1/me"));
        assertEquals(RouteTransactionMode.WRITE, transactionMode("GET", "/api/v1/unmatched"));
    }

    private static Set<Route> controllerRoutes() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.setEnvironment(new MockEnvironment()
            .withProperty("connex.public-api.enabled", "true"));
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<Route> routes = new HashSet<>();
        for (var candidate : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            Class<?> controller = ClassUtils.forName(
                candidate.getBeanClassName(), PublicApiRouteScopeArchTest.class.getClassLoader());
            RequestMapping classMapping =
                AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            Set<String> bases = paths(classMapping);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping =
                    AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                assertFalse(mapping.method().length == 0,
                    () -> "Public mapping must declare an HTTP method: " + method);
                for (String base : bases) {
                    for (String path : paths(mapping)) {
                        for (RequestMethod requestMethod : mapping.method()) {
                            String routePath = normalize(base, path);
                            if (routePath.equals("/api/v1") || routePath.startsWith("/api/v1/")) {
                                routes.add(new Route(
                                    HttpMethod.valueOf(requestMethod.name()), routePath));
                            }
                        }
                    }
                }
            }
        }
        return routes;
    }

    private static Set<String> paths(RequestMapping mapping) {
        if (mapping == null) {
            return Set.of("");
        }
        String[] paths = mapping.path().length == 0 ? mapping.value() : mapping.path();
        return paths.length == 0 ? Set.of("") : Set.copyOf(Arrays.asList(paths));
    }

    private static String normalize(String base, String path) {
        return (base + "/" + path).replaceAll("/{2,}", "/").replaceAll("/$", "");
    }

    private static RouteTransactionMode transactionMode(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/connex" + path);
        request.setContextPath("/connex");
        return PublicApiSecurityConfig.transactionMode(request);
    }

    private record Route(HttpMethod method, String path) {
    }
}
