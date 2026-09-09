package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.file.Files;
import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.test.util.ReflectionTestUtils;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.apisurface.ApiSurfaceInventory;

/** Exercises every approved anonymous mapping through the real security filters to a sentinel. */
@SpringBootTest(properties = "connex.public-api.enabled=false")
class ApiSurfaceAnonymousSecurityTest {
    @Autowired @Qualifier("springSecurityFilterChain") private Filter security;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;
    @Autowired private org.springframework.context.ApplicationContext context;

    @Test
    void approvedAnonymousRoutesReachTheApplicationAndOtherRoutesRemainBlocked() throws Exception {
        var builder = MockMvcBuilders.standaloneSetup(new Sentinel());
        var ordered = new java.util.TreeMap<Integer, java.util.List<Filter>>();
        boolean securityRegistered = false;
        for (var initializer : new org.springframework.boot.web.servlet.ServletContextInitializerBeans(context)) {
            if (!(initializer instanceof AbstractFilterRegistrationBean<?> registration)) {
                continue;
            }
            if (registration.isEnabled()) {
                assertTrue(registration.getUrlPatterns().isEmpty()
                    || registration.getUrlPatterns().equals(java.util.Set.of("/*")),
                    "Update harness to honor servlet URL patterns: " + registration.getUrlPatterns());
                Filter filter = registration.getFilter();
                if (filter instanceof DelegatingFilterProxy proxy) {
                    Object target = ReflectionTestUtils.getField(proxy, "targetBeanName");
                    if (!(target instanceof String name)) {
                        throw new IllegalStateException("Delegating filter target is required for perimeter coverage");
                    }
                    filter = context.getBean(name, Filter.class);
                }
                securityRegistered |= filter == security;
                ordered.computeIfAbsent(registration.getOrder(), ignored -> new java.util.ArrayList<>()).add(filter);
            }
        }
        assertTrue(securityRegistered, "The actual security servlet registration must be included");
        ordered.values().forEach(filters -> filters.forEach(builder::addFilters));
        var mvc = builder.build();
        var bootstrap = mvc.perform(request(HttpMethod.GET, "/api/auth/csrf")).andReturn().getResponse();
        assertEquals(204, bootstrap.getStatus(), "Anonymous CSRF bootstrap must reach the sentinel");
        String csrfToken = java.util.Objects.requireNonNull(bootstrap.getHeader("X-CSRF-TOKEN"),
            "The real CsrfFilter must issue a token");
        assertTrue(bootstrap.getCookies().length > 0, "Spring Session must issue the anonymous session cookie");
        Cookie[] cookies = java.util.stream.Stream.concat(java.util.Arrays.stream(bootstrap.getCookies()),
            java.util.stream.Stream.of(new Cookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, "a".repeat(64))))
            .toArray(Cookie[]::new);
        List<String> rows = Files.readAllLines(ApiSurfaceInventory.INVENTORY);
        int anonymous = 0;
        int protectedRoutes = 0;
        for (String row : rows.subList(1, rows.size())) {
            String[] fields = row.split("\t");
            String path = fields[1].replaceAll("\\{[^}]+}", "inventory-probe");
            if (path.startsWith("/api/v1/") || !fields[2].contains("#")) {
                continue;
            }
            var result = mvc.perform(request(HttpMethod.valueOf(fields[0]), path)
                .cookie(cookies).header("X-CSRF-TOKEN", csrfToken)).andReturn();
            int status = result.getResponse().getStatus();
            if (fields[3].startsWith("permitAll")) {
                assertEquals(204, status, fields[0] + " " + path + " must reach sentinel, not an earlier filter");
                anonymous++;
            } else {
                assertTrue(status == 401 || status == 403, fields[0] + " " + path + " returned " + status);
                protectedRoutes++;
            }
        }
        assertTrue(anonymous > 20, "Anonymous coverage must not become vacuous");
        assertTrue(protectedRoutes > 500, "Authenticated surface must remain covered");
        System.out.println("Anonymous perimeter: " + anonymous + " allowed mappings; " + protectedRoutes + " protected mappings rejected");
    }

    @Test
    void liveMvcMappingsAreCoveredAndUnsupportedFunctionalRoutesFailClosed() throws Exception {
        assertEquals(0, context.getBeanNamesForType(org.springframework.web.servlet.function.RouterFunction.class).length,
            "Add functional-router support to the inventory before publishing functional endpoints");
        var approved = new java.util.HashSet<String>();
        var rows = Files.readAllLines(ApiSurfaceInventory.INVENTORY);
        for (String row : rows.subList(1, rows.size())) {
            String[] fields = row.split("\t");
            approved.add(fields[0] + " " + fields[1] + " " + fields[2]);
        }
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            var handler = entry.getValue();
            for (String path : entry.getKey().getPatternValues()) {
                if (!path.startsWith("/api/") && !path.startsWith("/saml2/")) {
                    continue;
                }
                var declared = entry.getKey().getMethodsCondition().getMethods();
                var methods = declared.isEmpty()
                    ? java.util.Set.of(org.springframework.web.bind.annotation.RequestMethod.values()) : declared;
                for (var method : methods) {
                    String key = method.name() + " " + path + " " + handler.getBeanType().getName()
                        + "#" + handler.getMethod().getName();
                    assertTrue(approved.contains(key), "Live mapping absent from approved inventory: " + key);
                    checked++;
                }
            }
        }
        assertTrue(checked > 500, "Live handler coverage must not become vacuous");
        System.out.println("Live MVC mappings covered: " + checked);
    }

    @Test
    void headProfileCannotFallThroughTheAnonymousAuthWildcard() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Sentinel()).addFilters(security).build();
        int status = mvc.perform(request(HttpMethod.HEAD, "/api/auth/me")).andReturn().getResponse().getStatus();
        assertEquals(401, status, "HEAD inherits the GET profile handler and must require authentication");
    }

    /** A successful terminal response proves authorization was reached without invoking business operations. */
    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class Sentinel {
        @RequestMapping(value = "/**", method = {org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.HEAD, org.springframework.web.bind.annotation.RequestMethod.POST,
            org.springframework.web.bind.annotation.RequestMethod.PUT, org.springframework.web.bind.annotation.RequestMethod.DELETE,
            org.springframework.web.bind.annotation.RequestMethod.PATCH, org.springframework.web.bind.annotation.RequestMethod.OPTIONS})
        ResponseEntity<Void> reached(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response) {
            Object attribute = request.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
            if (attribute instanceof org.springframework.security.web.csrf.CsrfToken token) {
                response.setHeader(token.getHeaderName(), token.getToken());
            }
            return ResponseEntity.noContent().build();
        }
    }
}
