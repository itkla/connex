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
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.test.util.ReflectionTestUtils;

import com.zaxxer.hikari.HikariDataSource;
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
import ooo.klae.connex.backend.services.DataSubjectDisclosureJsonWriter;
import ooo.klae.connex.backend.services.DataSubjectDisclosureDelivery;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureNotes;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Exercises the actual servlet stream, independent audit visibility, admission and failure cleanup. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "server.servlet.context-path=",
        "server.tomcat.connection-timeout=15s"})
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
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private DataSubjectDisclosureJsonWriter disclosureWriter;
    @MockitoSpyBean private DataSubjectDisclosureDelivery delivery;
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
    private volatile DataSubjectDisclosureDelivery deadlineOverride;

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
        doAnswer(invocation -> {
            DataSubjectDisclosureDto disclosure = invocation.getArgument(0);
            StreamProbe current = probe.forOrganization(probe.context.getOrgId());
            current.noteSource.set(disclosure.getNotes());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    current.transactionCompletion.set(status);
                }
            });
            return invocation.callRealMethod();
        }).when(disclosureWriter).write(any(), any(), any());
        doAnswer(invocation -> {
            var deadline = deadlineOverride == null
                ? (DataSubjectDisclosureDelivery.Deadline) invocation.callRealMethod() : deadlineOverride.start();
            StreamProbe current = probe.forOrganization(probe.context.getOrgId());
            synchronized (deadline) {
                Runnable abort = (Runnable) ReflectionTestUtils.getField(deadline, "abort");
                assertNotNull(abort, "The actual servlet must bind its cancellable socket");
                ReflectionTestUtils.setField(deadline, "abort", (Runnable) () -> {
                    current.transportAborts.incrementAndGet();
                    current.transportAbortedAt.set(System.nanoTime());
                    current.abortThread.set(Thread.currentThread());
                    abort.run();
                });
            }
            current.deadline.set(deadline);
            return deadline;
        }).when(delivery).start();
    }

    @AfterEach
    void cleanup() {
        probe.release.countDown();
        if (deadlineOverride != null) {
            deadlineOverride.close();
        }
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
    void slowOrganizationCannotConsumeTheOtherOrganizationsSlot() throws Exception {
        Organization other = new Organization();
        other.setName("Other disclosure organization");
        other.setSlug("dsr-fair-" + UUID.randomUUID());
        organizations.insert(other);
        Workspace otherWorkspace = new Workspace();
        otherWorkspace.setOrgId(other.getId());
        otherWorkspace.setName("Other disclosure workspace");
        otherWorkspace.setSlug("dsr-fair-" + UUID.randomUUID());
        workspaces.insert(otherWorkspace);
        User otherActor = AuthenticatedSessions.account(users, "dsr-fair");
        workspaces.addMember(otherWorkspace.getId(), otherActor.getId(), "owner");
        jdbc.update("INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'owner')", other.getId(), otherActor.getId());
        jdbc.update("INSERT INTO person (workspace_id, name) VALUES (?, 'Other subject')", otherWorkspace.getId());
        int otherPerson = jdbc.queryForObject("SELECT id FROM person WHERE workspace_id = ?", Integer.class, otherWorkspace.getId());
        jdbc.update("INSERT INTO note (workspace_id, person_id, author_id, content) VALUES (?, ?, ?, 'Other note')",
            otherWorkspace.getId(), otherPerson, otherActor.getId());
        jdbc.update("""
            INSERT INTO data_subject_request
              (org_id, request_type, status, requester_name, subject_name, subject_workspace_id,
               subject_person_id, received_at, identity_verified_at)
            VALUES (?, 'disclosure', 'received', 'Other requester', 'Other subject', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, other.getId(), otherWorkspace.getId(), otherPerson);
        long otherRequest = jdbc.queryForObject("SELECT id FROM data_subject_request WHERE org_id = ?", Long.class, other.getId());
        String otherSession = session(sessions, other, otherActor);
        probe.peer = new StreamProbe(probe.context, dataSource);
        probe.peer.reset(other.getId(), otherWorkspace.getId(), otherActor.getId());
        try {
            String otherCookie = sessionCookie(otherSession);
            var bootstrap = raw("GET", "/api/auth/csrf", otherCookie, null, null, otherWorkspace.getId());
            assertEquals(200, bootstrap.statusCode());
            var token = json.readTree(bootstrap.body());
            probe.block = true;
            try (var executor = Executors.newSingleThreadExecutor()) {
                var first = executor.submit(this::disclose);
                assertTrue(probe.firstWrite.await(30, TimeUnit.SECONDS));
                try {
                    int readsBefore = probe.reads.get();
                    var repeated = disclose();
                    assertEquals(503, repeated.statusCode());
                    assertEquals("This deployment cannot serve the request", new String(repeated.body(), StandardCharsets.UTF_8));
                    assertEquals(readsBefore, probe.reads.get(), "Refusal must not open a second organization-A snapshot");
                    var second = raw("POST", "/api/orgs/" + other.getId() + "/data-subject-requests/" + otherRequest + "/disclosure",
                        otherCookie, token.path("headerName").asString(), token.path("token").asString(), otherWorkspace.getId());
                    assertEquals(200, second.statusCode(), new String(second.body(), StandardCharsets.UTF_8));
                    assertEquals("Other note", json.readTree(second.body()).path("notes").get(0).path("content").asString());
                    assertTrue(probe.peer.completed.await(10, TimeUnit.SECONDS));
                    assertEquals(1, probe.peer.firstWrites.get());
                    assertFalse(probe.completed.await(100, TimeUnit.MILLISECONDS), "Organization A still holds its slow delivery");
                    probe.peer.assertHealthy();
                } finally {
                    probe.release.countDown();
                }
                assertEquals(200, first.get(30, TimeUnit.SECONDS).statusCode());
            }
            assertEquals(1, auditCount());
            probe.assertHealthy();
        } finally {
            probe.release.countDown();
            probe.peer = null;
            sessions.deleteById(otherSession);
            jdbc.update("DELETE FROM note WHERE workspace_id = ?", otherWorkspace.getId());
            jdbc.update("DELETE FROM person WHERE workspace_id = ?", otherWorkspace.getId());
            jdbc.update("DELETE FROM data_subject_request WHERE org_id = ?", other.getId());
            jdbc.update("DELETE FROM workspace WHERE id = ?", otherWorkspace.getId());
            jdbc.update("DELETE FROM organization WHERE id = ?", other.getId());
            jdbc.update("DELETE FROM app_user WHERE id = ?", otherActor.getId());
        }
    }

    @Test
    void stalledClientReturnsTheConnectionPermitAndSourceWithinTheAbsoluteDeliveryBound() throws Exception {
        assertDeliveryDeadline(false);
    }

    @Test
    void tricklingClientCannotExtendTheAbsoluteDeliveryBound() throws Exception {
        assertDeliveryDeadline(true);
    }

    private void assertDeliveryDeadline(boolean trickle) throws Exception {
        deadlineOverride = new DataSubjectDisclosureDelivery(10_000, dataSource);
        jdbc.update("UPDATE note SET content = REPEAT('x', 131072) WHERE workspace_id = ?", workspace.getId());
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(1_024);
            socket.setSoTimeout(1_000);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port));
            String request = "POST " + path() + " HTTP/1.0\r\nHost: 127.0.0.1:" + port
                + "\r\nCookie: " + cookie + "\r\nX-Workspace-Id: " + workspace.getId()
                + "\r\n" + csrfHeader + ": " + csrfToken + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(probe.firstWrite.await(60, TimeUnit.SECONDS), "Preflight and audit must precede response delivery");
            socket.setSoTimeout(5_000);
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            while (!headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                int value = socket.getInputStream().read();
                assertTrue(value >= 0 && headers.size() < 65_536);
                headers.write(value);
            }
            assertTrue(headers.toString(StandardCharsets.US_ASCII).contains(" 200 "));
            assertFalse(headers.toString(StandardCharsets.US_ASCII).toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked"));
            socket.setSoTimeout(1_000);
            ByteArrayOutputStream partial = new ByteArrayOutputStream();
            int firstBodyByte = socket.getInputStream().read();
            assertEquals('{', firstBodyByte);
            partial.write(firstBodyByte);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var reader = executor.submit(() -> {
                    if (trickle) {
                        while (!probe.completed.await(100, TimeUnit.MILLISECONDS)) {
                            try {
                                byte[] bytes = new byte[1_024];
                                int count = socket.getInputStream().read(bytes);
                                if (count < 0) {
                                    break;
                                }
                                partial.write(bytes, 0, count);
                            } catch (SocketTimeoutException ignored) {
                                if (probe.completed.getCount() == 0) {
                                    break;
                                }
                            } catch (IOException closedByDeadline) {
                                break;
                            }
                        }
                    }
                    return null;
                });
                assertTrue(probe.completed.await(30, TimeUnit.SECONDS), "Deadline plus the 15-second connector write timeout must end delivery");
                reader.get(5, TimeUnit.SECONDS);
            }
            var deadline = probe.deadline.get();
            assertNotNull(deadline);
            assertEquals(true, ReflectionTestUtils.getField(deadline, "expired"), "Absolute deadline must cancel even an active trickling socket");
            long startedAt = (long) ReflectionTestUtils.getField(deadline, "startedAt");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(elapsedMillis < 25_000, "Delivery cleanup exceeded deadline plus one connector timeout: " + elapsedMillis);
            assertEquals(1, probe.transportAborts.get());
            assertTrue(probe.transportAbortedAt.get() - startedAt < Duration.ofSeconds(12).toNanos(),
                "The real transport cancellation must occur at the absolute deadline, before native idle timeout");
            if (!trickle) {
                assertEquals("disclosure-delivery-deadline", probe.abortThread.get().getName());
            }
            assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK, probe.transactionCompletion.get());
            assertTrue(probe.sourceInvalidated.get());
            assertEquals(0, dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections());
            assertEquals(1, auditCount());
            probe.assertHealthy();
            if (trickle) {
                assertTrue(partial.size() > 10_240, "Client must consume multiple TCP windows before cancellation");
                assertTrue(probe.progressWrites.get() >= 3, "Server writes must keep progressing while the client trickles");
                assertTrue(probe.lastProgressAt.get() - (long) ReflectionTestUtils.getField(deadline, "startedAt")
                    >= Duration.ofSeconds(5).toNanos(), "Progress must span the delivery window, rather than only initial buffering");
            }
            try {
                socket.getInputStream().transferTo(partial);
            } catch (IOException closedByDeadline) {
                assertTrue(probe.sourceInvalidated.get());
            }
            assertTrue(partial.size() < 250L * 131_072);
            assertThrows(RuntimeException.class, () -> json.readTree(partial.toByteArray()));
            assertFalse(partial.toString(StandardCharsets.UTF_8).contains("This deployment cannot serve the request"));
            System.out.println("DSR " + (trickle ? "trickling" : "stalled") + " delivery cleanup ms=" + elapsedMillis);
        }
        jdbc.update("UPDATE note SET content = 'Retry note' WHERE workspace_id = ?", workspace.getId());
        probe.reset(org.getId(), workspace.getId(), actor.getId());
        probe.expectedAudits = 2;
        assertEquals(200, disclose().statusCode(), "The organization permit must be reusable after deadline cleanup");
        assertEquals(2, auditCount());
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
        return raw(method, path, sessionCookie, header, token, workspace.getId());
    }

    private HttpResponse<byte[]> raw(String method, String path, String sessionCookie,
            String header, String token, int workspaceId) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(60)).header("X-Workspace-Id", Integer.toString(workspaceId));
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
        return session(repository, org, actor);
    }

    private <S extends Session> String session(SessionRepository<S> repository, Organization org, User actor) {
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
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> smallDisclosureSocketBuffers() {
            return factory -> factory.addConnectorCustomizers(connector -> connector.setProperty("socket.txBufSize", "1024"));
        }

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
        private final AtomicInteger progressWrites = new AtomicInteger();
        private final AtomicLong lastProgressAt = new AtomicLong();
        private final AtomicInteger transportAborts = new AtomicInteger();
        private final AtomicLong transportAbortedAt = new AtomicLong();
        private final AtomicReference<Thread> abortThread = new AtomicReference<>();
        private final AtomicReference<DataSubjectDisclosureNotes> noteSource = new AtomicReference<>();
        private final AtomicReference<DataSubjectDisclosureDelivery.Deadline> deadline = new AtomicReference<>();
        private final AtomicInteger transactionCompletion = new AtomicInteger(-1);
        private final AtomicBoolean sourceInvalidated = new AtomicBoolean();
        private volatile StreamProbe peer;
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
            progressWrites.set(0);
            lastProgressAt.set(0);
            transportAborts.set(0);
            transportAbortedAt.set(0);
            abortThread.set(null);
            noteSource.set(null);
            deadline.set(null);
            transactionCompletion.set(-1);
            sourceInvalidated.set(false);
            firstWrite = new CountDownLatch(1);
            release = new CountDownLatch(1);
            completed = new CountDownLatch(1);
            expectedAudits = 1;
            failAfter = Integer.MAX_VALUE;
            block = false;
        }

        StreamProbe forOrganization(int organizationId) {
            return peer != null && peer.orgId == organizationId ? peer : this;
        }

        void read() {
            StreamProbe current = forOrganization(context.getOrgId());
            if (current != this) {
                current.read();
                return;
            }
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
            if (peer != null && request.getRequestURI().startsWith("/api/orgs/" + peer.orgId + "/")) {
                peer.doFilterInternal(request, response, chain);
                return;
            }
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
                                if (accepted > 0) {
                                    progressWrites.incrementAndGet();
                                    lastProgressAt.set(System.nanoTime());
                                }
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
                    assertEquals(null, context.getCatalog());
                    if (owner.get() == Thread.currentThread() && noteSource.get() != null) {
                        assertEquals(null, ReflectionTestUtils.getField(noteSource.get(), "source"));
                        sourceInvalidated.set(true);
                    }
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
