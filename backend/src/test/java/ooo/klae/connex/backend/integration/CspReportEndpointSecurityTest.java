package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.CspReportCookieFilter;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;

/**
 * Drives the collector over the real Spring Session and Spring Security filter chains.
 *
 * <p>The session-bearing cases use a store-backed session and its cookie rather than a mock
 * session, because the property under test is that the collector never resolves the session at
 * all: Spring Session rewrites the last-accessed time the moment anything does, and a mock session
 * attached directly to the request would bypass the resolution being guarded.
 */
@SpringBootTest
class CspReportEndpointSecurityTest {
    private static final MediaType CSP_REPORT = MediaType.parseMediaType("application/csp-report");
    private static final String LEGACY_BODY = """
            {"csp-report":{"document-uri":"https://connex.example.com/dashboard?token=secret",
            "effective-directive":"img-src","blocked-uri":"https://cdn.example.invalid/logo.png",
            "disposition":"enforce","status-code":200}}
            """;

    @Autowired private WebApplicationContext context;
    @Autowired private UserMapper userMapper;
    @Autowired private SessionRepository<? extends Session> sessionRepository;
    @Autowired private CookieSerializer cookieSerializer;
    @Autowired private FilterRegistrationBean<CspReportCookieFilter> cspReportCookieFilterRegistration;
    @Autowired @Qualifier("springSessionRepositoryFilter") private Filter springSessionRepositoryFilter;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;

    private MockMvc mockMvc;
    private User account;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(cspReportCookieFilterRegistration.getFilter(),
                        springSessionRepositoryFilter, springSecurityFilterChain)
                .build();
        account = AuthenticatedSessions.account(userMapper, "csp-report");
    }

    @Test
    void anonymousReportsAreAcceptedWithoutCsrfAndStartNoSession() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void sessionBearingReportsAreAcceptedWithoutCsrfAndDespiteAStaleWorkspacePin() throws Exception {
        Cookie sessionCookie = sessionCookie(storedAuthenticatedSession());

        mockMvc.perform(post("/api/csp-reports").cookie(sessionCookie)
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/csp-reports").cookie(sessionCookie)
                        .cookie(new Cookie("connex_workspace", String.valueOf(Integer.MAX_VALUE)))
                        .header("X-Workspace-Id", Integer.MAX_VALUE)
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent());
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
     * The ordering the collector's session-neutrality rests on, which the assembled MockMvc chain
     * above states rather than proves: in the running application the cookie filter is only ahead
     * of Spring Session because its registration says so.
     */
    @Test
    void theCookieFilterRunsBeforeSpringSession() {
        assertTrue(cspReportCookieFilterRegistration.getOrder() < SessionRepositoryFilter.DEFAULT_ORDER);
    }

    @Test
    void garbageBodiesStillAnswerNoContent() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(CSP_REPORT).content("not json"))
                .andExpect(status().isNoContent());
    }

    @Test
    void readsAndWritesOnOtherMethodsAreNotPermittedAndStartNoSession() throws Exception {
        mockMvc.perform(get("/api/csp-reports"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(put("/api/csp-reports").with(csrf().asHeader())
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherContentTypesAreRefused() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    private String storedAuthenticatedSession() {
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
    private Cookie sessionCookie(String sessionId) {
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
