package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.TenantExportService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ContentPublication;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.StoredObject;

/** Exercises real snapshot queries and control authority at the actual byte publication boundary. */
class AiAssistantPrivacyExportIntegrationTest extends AbstractAiAgentPrivacyIntegrationTest {
    @Autowired private TenantExportService exports;
    @Autowired private AiAssistantPrivacyService authority;
    @MockitoSpyBean private AiAssistantPrivacyPublicationGuard publication;
    @MockitoSpyBean private SessionSecurityService sessionSecurity;

    @BeforeEach
    void allowAuthorizedExport() {
        jdbc.update("INSERT INTO org_member (org_id,user_id,org_role) VALUES (?,?,'admin')",
                workspace.getOrgId(), currentUser.getId());
        doNothing().when(sessionSecurity).requireRecentAuthentication(currentUser.getId());
    }

    @Test
    void invalidationAbortsOpenRepeatableReadExport() throws Exception {
        session("old snapshot marker", 1);
        var download = exports.prepare(workspace.getOrgId(), workspace.getId(), currentUser.getId());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        gateActualWrite(reached, release);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> {
                try {
                    download.writeTo(output);
                    return null;
                } catch (IOException failure) {
                    return failure;
                }
            });
            assertTrue(reached.await(10, TimeUnit.SECONDS));
            transaction().executeWithoutResult(status -> authority.invalidateOrganization(
                    workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
            release.countDown();
            assertInstanceOf(IOException.class, writer.get(10, TimeUnit.SECONDS));
            assertEquals(0, output.size());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_operation_lease WHERE workspace_id=?",
                    Integer.class, workspace.getId()));
        } finally {
            release.countDown();
            download.cancel();
        }
    }

    @Test
    void chatAttachmentBytesStopAfterInvalidation() throws Exception {
        byte[] bytes = new byte[32_768];
        AtomicBoolean closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(bytes) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        var output = new ByteArrayOutputStream();
        long generation = authority.current(workspace.getOrgId());
        try (var content = new ManagedContent(new StoredObject(input, bytes.length), "text/plain", "chat.txt",
                new ContentPublication(publication, workspace.getOrgId(), generation))) {
            AtomicBoolean first = new AtomicBoolean(true);
            doAnswer(call -> {
                call.callRealMethod();
                if (first.compareAndSet(true, false)) {
                    transaction().executeWithoutResult(status -> authority.invalidateOrganization(
                            workspace.getOrgId(), UUID.randomUUID(), "restriction", null, null));
                }
                return null;
            }).when(publication).withCurrent(anyInt(), anyLong(), any());
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> content.writeTo(output));
            assertEquals(16_384, output.size());
        }
        assertTrue(closed.get());
    }

    @Test
    void newExportOmitsOldRowsAndObjects() throws Exception {
        int old = session("withheld export marker", 0);
        session("current export marker", 1);
        jdbc.update("INSERT INTO attachment (workspace_id,entity_type,entity_id,file_name,url) VALUES (?,'ai_chat_session',?,'old.txt',?)",
                workspace.getId(), old, "/api/attachments/content/" + UUID.randomUUID() + ".txt");
        var download = exports.prepare(workspace.getOrgId(), workspace.getId(), currentUser.getId());
        var output = new ByteArrayOutputStream();
        try {
            download.writeTo(output);
            StringBuilder contents = new StringBuilder();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
                while (zip.getNextEntry() != null) {
                    contents.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertTrue(contents.toString().contains("current export marker"));
            assertFalse(contents.toString().contains("withheld export marker"));
            assertFalse(contents.toString().contains("old.txt"));
        } finally {
            download.cancel();
            jdbc.update("DELETE FROM attachment WHERE workspace_id=?", workspace.getId());
        }
    }

    private void gateActualWrite(CountDownLatch reached, CountDownLatch release) throws IOException {
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            if (first.compareAndSet(true, false)) {
                reached.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Output contention fixture timed out");
                }
            }
            return call.callRealMethod();
        }).when(publication).withCurrent(anyInt(), anyLong(), any());
    }

    private int session(String content, long generation) {
        jdbc.update("INSERT INTO ai_chat_session (workspace_id,created_by_user_id,title,visibility,status,organization_privacy_generation) VALUES (?,?,'Export fixture','private','active',?)",
                workspace.getId(), currentUser.getId(), generation);
        int id = java.util.Objects.requireNonNull(jdbc.queryForObject(
                "SELECT MAX(id) FROM ai_chat_session WHERE workspace_id=?", Integer.class, workspace.getId()));
        jdbc.update("INSERT INTO ai_chat_message (workspace_id,session_id,seq,author_kind,content,organization_privacy_generation) VALUES (?,?,1,'user',?,?)",
                workspace.getId(), id, content, generation);
        return id;
    }
}
