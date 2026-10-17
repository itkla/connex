package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.DataSubjectDisclosureMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Exercises the actual servlet stream, independent audit visibility, admission and failure cleanup. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "server.servlet.context-path="})
@Import(DataSubjectDisclosureStreamingIntegrationTest.ProbeConfiguration.class)
class DataSubjectDisclosureStreamingIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrganizationMapper organizations;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private UserMapper users;
    @Autowired private SessionRepository<? extends Session> sessions;
    @Autowired private CookieSerializer cookies;
    @Autowired private ObjectMapper json;
    @Autowired private StreamProbe probe;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private DataSubjectDisclosureMapper disclosureMapper;
    @LocalServerPort private int port;
    private Organization org;
    private Workspace workspace;
    private User actor;
    private String sessionId;
    private String cookie;
    private String csrfHeader;
    private String csrfToken;
    private int personId;
    private long requestId;

    @BeforeEach
    void fixtures() throws Exception {
        org = new Organization();
        org.setName("Streaming disclosure organization");
        org.setSlug("dsr-stream-" + UUID.randomUUID());
        organizations.insert(org);
        workspace = new Workspace();
        workspace.setOrgId(org.getId());
        workspace.setName("Streaming disclosure workspace");
        workspace.setSlug("dsr-stream-" + UUID.randomUUID());
        workspaces.insert(workspace);
        actor = AuthenticatedSessions.account(users, "dsr-stream");
        workspaces.addMember(workspace.getId(), actor.getId(), "owner");
        jdbc.update("INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'owner')", org.getId(), actor.getId());
        jdbc.update("INSERT INTO person (workspace_id, name) VALUES (?, 'Streaming subject')", workspace.getId());
        personId = jdbc.queryForObject("SELECT id FROM person WHERE workspace_id = ?", Integer.class, workspace.getId());
        for (int index = 0; index < 250; index++) {
            jdbc.update("INSERT INTO note (workspace_id, person_id, author_id, content, visibility) VALUES (?, ?, ?, ?, ?)",
                workspace.getId(), personId, actor.getId(), "内容 note " + index + "x".repeat(2048), index % 2 == 0 ? "private" : "workspace");
        }
        jdbc.update("""
            INSERT INTO data_subject_request
              (org_id, request_type, status, requester_name, subject_name, subject_workspace_id,
               subject_person_id, received_at, identity_verified_at)
            VALUES (?, 'disclosure', 'received', 'Streaming requester', 'Streaming subject', ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, org.getId(), workspace.getId(), personId);
        requestId = jdbc.queryForObject("SELECT id FROM data_subject_request WHERE org_id = ?", Long.class, org.getId());
        sessionId = session(sessions);
        cookie = sessionCookie(sessionId);
        var csrf = raw("GET", "/api/auth/csrf", false, cookie);
        assertEquals(200, csrf.statusCode());
        var token = json.readTree(csrf.body());
        csrfHeader = token.path("headerName").asString();
        csrfToken = token.path("token").asString();
        probe.reset(org.getId(), workspace.getId(), actor.getId());
        DataSubjectDisclosureMapper realMapper = sqlSession.getMapper(DataSubjectDisclosureMapper.class);
        doAnswer(invocation -> {
            probe.read();
            return realMapper.findNoteIds(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), invocation.getArgument(3));
        }).when(disclosureMapper).findNoteIds(anyInt(), anyInt(), anyList(), any());
    }

    @AfterEach
    void cleanup() {
        probe.release.countDown();
        sessions.deleteById(sessionId);
        jdbc.update("DELETE FROM note WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM data_subject_request WHERE org_id = ?", org.getId());
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        jdbc.update("DELETE FROM organization WHERE id = ?", org.getId());
        jdbc.update("DELETE FROM app_user WHERE id = ?", actor.getId());
    }

    @Test
    void firstServletByteFollowsCommittedAuditAndEveryReadAndWriteUsesTheRequestThread() throws Exception {
        var response = disclose();
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(250, json.readTree(response.body()).path("notes").size());
        assertEquals(8, probe.reads.get());
        assertEquals(1, probe.firstWrites.get());
        assertEquals(1, probe.cleanups.get());
        assertEquals(Long.toString(response.body().length), response.headers().firstValue("X-Disclosure-Byte-Length").orElseThrow());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body())),
            response.headers().firstValue("X-Disclosure-SHA256").orElseThrow());
        assertEquals("250", response.headers().firstValue("X-Disclosure-Note-Count").orElseThrow());
        probe.assertHealthy();
    }

    @Test
    void committedMidStreamFailureLeavesTruncatedJsonAndDurableAuditThenExplicitRetryWorks() throws Exception {
        probe.failAfter = 16_384;
        var response = disclose();
        assertEquals(200, response.statusCode());
        assertTrue(response.body().length >= 16_384);
        assertTrue(response.body().length < Long.parseLong(response.headers().firstValue("X-Disclosure-Byte-Length").orElseThrow()));
        assertThrows(RuntimeException.class, () -> json.readTree(response.body()));
        String partial = new String(response.body(), StandardCharsets.UTF_8);
        assertFalse(partial.contains("This deployment cannot serve the request"));
        assertFalse(partial.contains("Disclosure release did not complete"));
        assertEquals(1, auditCount());
        assertEquals(1, probe.cleanups.get());
        probe.assertHealthy();
        probe.reset(org.getId(), workspace.getId(), actor.getId());
        probe.expectedAudits = 2;
        assertEquals(200, disclose().statusCode());
        assertEquals(2, auditCount());
        probe.assertHealthy();
    }

    @Test
    void overlappingDisclosureRefusesWithExisting503BeforeOpeningAnotherSnapshot() throws Exception {
        probe.block = true;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(this::disclose);
            assertTrue(probe.firstWrite.await(30, TimeUnit.SECONDS));
            int before = probe.reads.get();
            try {
                var overloaded = disclose();
                assertEquals(503, overloaded.statusCode());
                assertEquals("This deployment cannot serve the request", new String(overloaded.body(), StandardCharsets.UTF_8));
                assertEquals(before, probe.reads.get());
                assertEquals(1, auditCount());
            } finally {
                probe.release.countDown();
            }
            assertEquals(200, first.get(30, TimeUnit.SECONDS).statusCode());
        }
        probe.assertHealthy();
    }

    @Test
    void actualHttpKeepsAnonymousCsrfAndForeignOrganizationRefusals() throws Exception {
        String anonymousId = anonymousSession(sessions);
        try {
            String anonymousCookie = sessionCookie(anonymousId);
            var bootstrap = raw("GET", "/api/auth/csrf", false, anonymousCookie);
            assertEquals(200, bootstrap.statusCode());
            var anonymousToken = json.readTree(bootstrap.body());
            assertEquals(401, raw("POST", path(), anonymousCookie,
                anonymousToken.path("headerName").asString(), anonymousToken.path("token").asString()).statusCode());
        } finally {
            sessions.deleteById(anonymousId);
        }
        assertEquals(403, raw("POST", path(), false, cookie).statusCode());
        assertEquals(403, raw("POST", "/api/orgs/2147483646/data-subject-requests/" + requestId + "/disclosure", true, cookie).statusCode());
        assertEquals(0, auditCount());
        assertEquals(0, probe.reads.get());
    }

    private HttpResponse<byte[]> disclose() throws Exception {
        var response = raw("POST", path(), true, cookie);
        if (response.statusCode() == 200) {
            assertTrue(probe.completed.await(10, TimeUnit.SECONDS), "Request cleanup must complete");
        }
        return response;
    }

    private String path() {
        return "/api/orgs/" + org.getId() + "/data-subject-requests/" + requestId + "/disclosure";
    }

    private HttpResponse<byte[]> raw(String method, String path, boolean csrf, String sessionCookie) throws Exception {
        return raw(method, path, sessionCookie, csrf ? csrfHeader : null, csrf ? csrfToken : null);
    }

    private HttpResponse<byte[]> raw(String method, String path, String sessionCookie,
            String header, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(60)).header("X-Workspace-Id", Integer.toString(workspace.getId()));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        if (header != null) {
            builder.header(header, token);
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private String sessionCookie(String id) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookies.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, id));
        return response.getHeader("Set-Cookie").split(";", 2)[0];
    }

    private <S extends Session> String anonymousSession(SessionRepository<S> repository) {
        S session = repository.createSession();
        repository.save(session);
        return session.getId();
    }

    private <S extends Session> String session(SessionRepository<S> repository) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities())));
        long now = System.currentTimeMillis();
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, actor.getId());
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, actor.getSessionEpoch());
        int credential = OwnershipOfferTestFixtures.passkey(jdbc, actor);
        jdbc.update("INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source)"
            + " VALUES (?, ?, 'FOUNDER')", credential, org.getId());
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, actor.getId());
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_PROOF_ATTR, actor.getId() + ":" + now + ":" + credential);
        repository.save(session);
        return session.getId();
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE org_id = ? AND action = 'appi.subject_request.disclosure'",
            Integer.class, org.getId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        StreamProbe disclosureStreamProbe(TenantContext context, DataSource dataSource) {
            return new StreamProbe(context, dataSource);
        }
    }

    /** Instruments the real servlet target without buffering response bytes. */
    static final class StreamProbe extends OncePerRequestFilter {
        private final TenantContext context;
        private final DataSource dataSource;
        private final AtomicReference<Thread> owner = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger firstWrites = new AtomicInteger();
        private final AtomicInteger cleanups = new AtomicInteger();
        private volatile CountDownLatch firstWrite = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(1);
        private volatile CountDownLatch completed = new CountDownLatch(1);
        private volatile int orgId;
        private volatile int workspaceId;
        private volatile int actorId;
        private volatile int expectedAudits = 1;
        private volatile int failAfter = Integer.MAX_VALUE;
        private volatile boolean block;

        StreamProbe(TenantContext context, DataSource dataSource) {
            this.context = context;
            this.dataSource = dataSource;
        }

        void reset(int orgId, int workspaceId, int actorId) {
            this.orgId = orgId;
            this.workspaceId = workspaceId;
            this.actorId = actorId;
            owner.set(null);
            failure.set(null);
            reads.set(0);
            firstWrites.set(0);
            cleanups.set(0);
            firstWrite = new CountDownLatch(1);
            release = new CountDownLatch(1);
            completed = new CountDownLatch(1);
            expectedAudits = 1;
            failAfter = Integer.MAX_VALUE;
            block = false;
        }

        void read() {
            try {
                owner.compareAndSet(null, Thread.currentThread());
                assertScope();
                reads.incrementAndGet();
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
                throw problem;
            }
        }

        void assertHealthy() {
            if (failure.get() != null) {
                throw new AssertionError("Servlet stream probe failed", failure.get());
            }
        }

        private void assertScope() {
            assertSame(owner.get(), Thread.currentThread());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(workspaceId, context.getWorkspaceId());
            assertEquals(orgId, context.getOrgId());
            assertEquals(actorId, context.getUserId());
            assertEquals("org_admin", context.getRole());
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().endsWith("/disclosure");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            HttpServletResponseWrapper wrapper = new HttpServletResponseWrapper(response) {
                private ServletOutputStream stream;
                @Override public ServletOutputStream getOutputStream() throws IOException {
                    if (stream == null) {
                        ServletOutputStream target = super.getOutputStream();
                        stream = new ServletOutputStream() {
                            private int written;
                            @Override public boolean isReady() { return target.isReady(); }
                            @Override public void setWriteListener(WriteListener listener) { target.setWriteListener(listener); }
                            @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }
                            @Override public void flush() throws IOException { target.flush(); }
                            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                                if (response.getStatus() != 200 || owner.get() != Thread.currentThread()) {
                                    target.write(bytes, offset, length);
                                    return;
                                }
                                try {
                                    assertScope();
                                    if (written == 0) {
                                        try (var connection = dataSource.getConnection();
                                                var statement = connection.prepareStatement(
                                                    "SELECT changes FROM audit_log WHERE org_id = ? AND action = 'appi.subject_request.disclosure' ORDER BY id")) {
                                            statement.setInt(1, orgId);
                                            try (var result = statement.executeQuery()) {
                                                int count = 0;
                                                while (result.next()) {
                                                    count++;
                                                    assertTrue(result.getString(1).contains("jsonSha256"));
                                                }
                                                assertEquals(expectedAudits, count, "Audit must commit before the first servlet byte");
                                            }
                                        }
                                        firstWrites.incrementAndGet();
                                        firstWrite.countDown();
                                        if (block) {
                                            assertTrue(release.await(30, TimeUnit.SECONDS));
                                        }
                                    }
                                } catch (Throwable problem) {
                                    failure.compareAndSet(null, problem);
                                    throw new IOException("Stream invariant failed", problem);
                                }
                                int accepted = Math.min(length, Math.max(0, failAfter - written));
                                target.write(bytes, offset, accepted);
                                written += accepted;
                                if (accepted < length) {
                                    target.flush();
                                    throw new IOException("Injected mid-stream disconnect");
                                }
                            }
                        };
                    }
                    return stream;
                }
            };
            try {
                chain.doFilter(request, wrapper);
            } finally {
                try {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    assertFalse(context.isResolved());
                    cleanups.incrementAndGet();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    if (owner.get() == Thread.currentThread()) {
                        completed.countDown();
                    }
                }
            }
        }
    }
}
