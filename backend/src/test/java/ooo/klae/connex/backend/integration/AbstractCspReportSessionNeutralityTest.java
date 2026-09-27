package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.RegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.CspReportCookieFilter;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;

/**
 * The collector's session-neutrality, driven over the real Spring Session and Spring Security
 * filter chains.
 *
 * <p>The session-bearing cases use a store-backed session and its cookie rather than a mock
 * session, because the property under test is that the collector never resolves the session at
 * all: Spring Session rewrites the last-accessed time the moment anything does, and a mock session
 * attached directly to the request would bypass the resolution being guarded.
 *
 * <p>The cookie filter and Spring Session's filter are assembled in the order their registrations
 * give them rather than in a hand-written order, so a change to either registration — including
 * {@code spring.session.servlet.filter-order} — moves this suite with the running application
 * instead of leaving it green against an order production no longer uses.
 *
 * <p>Subclassed once per {@code spring.session.servlet.filter-order} worth testing: the property is
 * an operator-facing knob, and the guarantee has to hold for every value of it, not only the
 * library default.
 */
abstract class AbstractCspReportSessionNeutralityTest {
    protected static final MediaType CSP_REPORT = MediaType.parseMediaType("application/csp-report");
    protected static final String LEGACY_BODY = """
            {"csp-report":{"document-uri":"https://connex.example.com/dashboard?token=secret",
            "effective-directive":"img-src","blocked-uri":"https://cdn.example.invalid/logo.png",
            "disposition":"enforce","status-code":200}}
            """;

    @Autowired protected WebApplicationContext context;
    @Autowired protected UserMapper userMapper;
    @Autowired protected SessionRepository<? extends Session> sessionRepository;
    @Autowired protected CookieSerializer cookieSerializer;
    @Autowired protected FilterRegistrationBean<CspReportCookieFilter> cspReportCookieFilterRegistration;
    @Autowired @Qualifier("sessionRepositoryFilterRegistration")
    protected RegistrationBean sessionRepositoryFilterRegistration;
    @Autowired @Qualifier("springSessionRepositoryFilter") protected Filter springSessionRepositoryFilter;
    @Autowired @Qualifier("springSecurityFilterChain") protected Filter springSecurityFilterChain;

    protected MockMvc mockMvc;
    protected User account;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionFiltersInRegisteredOrder())
                .addFilters(springSecurityFilterChain)
                .build();
        account = AuthenticatedSessions.account(userMapper, "csp-report");
    }

    /**
     * A recurring violation must not keep an idle login alive: the collector never resolves the
     * session, so its last-accessed time stays where the previous real request left it. The
     * authenticated read afterwards proves the same cookie does move it when a chain asks.
     */
    @Test
    void reportsNeverTouchTheSessionTheyCarry() throws Exception {
        String sessionId = storedAuthenticatedSession();
        Cookie sessionCookie = sessionCookie(sessionId);
        Instant beforeReport = lastAccessedTime(sessionId);
        Thread.sleep(5);

        mockMvc.perform(post("/api/csp-reports").cookie(sessionCookie)
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertEquals(beforeReport, lastAccessedTime(sessionId));

        mockMvc.perform(get("/api/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk());

        assertTrue(lastAccessedTime(sessionId).isAfter(beforeReport));
    }

    /**
     * The ordering the collector's session-neutrality rests on: in the running application the
     * cookie filter is only ahead of Spring Session because the two registrations say so, and
     * Spring Session's order is a configurable property rather than the library constant — so the
     * cookie filter's own order is derived from that registration, not from the constant.
     */
    @Test
    void theCookieFilterRunsBeforeSpringSession() {
        assertTrue(cspReportCookieFilterRegistration.getOrder()
                < sessionRepositoryFilterRegistration.getOrder());
    }

    /**
     * The cookie filter and Spring Session's filter, in the order the application registers them.
     */
    private Filter[] sessionFiltersInRegisteredOrder() {
        Filter cookieFilter = cspReportCookieFilterRegistration.getFilter();
        return cspReportCookieFilterRegistration.getOrder()
                < sessionRepositoryFilterRegistration.getOrder()
                ? new Filter[] {cookieFilter, springSessionRepositoryFilter}
                : new Filter[] {springSessionRepositoryFilter, cookieFilter};
    }

    protected String storedAuthenticatedSession() {
        return storeAuthenticated(sessionRepository, account);
    }

    private static <S extends Session> String storeAuthenticated(
            SessionRepository<S> repository, User account) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                        account, null, account.getAuthorities())));
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, account.getSessionEpoch());
        repository.save(session);
        return session.getId();
    }

    private Instant lastAccessedTime(String sessionId) {
        Session session = sessionRepository.findById(sessionId);
        assertNotNull(session);
        return session.getLastAccessedTime();
    }

    /**
     * The cookie a browser would send for this session.
     *
     * <p>Built by asking the configured serializer to write one rather than by encoding the id
     * here: name and encoding are context configuration, and a hand-rolled cookie that fails to
     * resolve would leave every assertion below passing for the wrong reason.
     */
    protected Cookie sessionCookie(String sessionId) {
        MockHttpServletResponse written = new MockHttpServletResponse();
        cookieSerializer.writeCookieValue(new CookieSerializer.CookieValue(
                new MockHttpServletRequest(), written, sessionId));
        String header = written.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(header);
        String pair = header.split(";", 2)[0];
        int separator = pair.indexOf('=');
        assertTrue(separator > 0);
        return new Cookie(pair.substring(0, separator), pair.substring(separator + 1));
    }
}
