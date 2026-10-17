package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.DataSubjectDisclosureAuditAccess;
import ooo.klae.connex.backend.services.DataSubjectDisclosureJsonWriter;
import ooo.klae.connex.backend.services.DataSubjectDisclosureReadTransaction;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves physical connection and snapshot continuity across an independently committed audit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DataSubjectDisclosureSnapshotIntegrationTest {
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrganizationMapper organizations;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private UserMapper users;
    @Autowired private TenantContext context;
    @Autowired private TenantWorkScope scope;
    @Autowired private DataSubjectDisclosureReadTransaction reads;
    @Autowired private DataSubjectDisclosureAuditAccess audit;
    @Autowired private DataSubjectDisclosureJsonWriter writer;
    @Autowired private ObjectMapper mapper;
    private Organization org;
    private Workspace workspace;
    private User actor;
    private int personId;
    private int updatedId;
    private int deletedId;

    @BeforeEach
    void fixtures() {
        org = new Organization();
        org.setName("Disclosure snapshot organization");
        org.setSlug("dsr-snapshot-" + UUID.randomUUID());
        organizations.insert(org);
        workspace = new Workspace();
        workspace.setOrgId(org.getId());
        workspace.setName("Disclosure snapshot workspace");
        workspace.setSlug("dsr-snapshot-" + UUID.randomUUID());
        workspaces.insert(workspace);
        actor = AuthenticatedSessions.account(users, "dsr-snapshot");
        workspaces.addMember(workspace.getId(), actor.getId(), "owner");
        jdbc.update("INSERT INTO person (workspace_id, name) VALUES (?, 'Snapshot subject')", workspace.getId());
        personId = jdbc.queryForObject("SELECT id FROM person WHERE workspace_id = ?", Integer.class, workspace.getId());
        updatedId = note("before update");
        deletedId = note("before delete");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
    }

    @AfterEach
    void cleanup() {
        context.clear();
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM note WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        jdbc.update("DELETE FROM organization WHERE id = ?", org.getId());
        jdbc.update("DELETE FROM app_user WHERE id = ?", actor.getId());
    }

    @Test
    void updateDeleteAndInsertCommittedBetweenPassesDoNotChangeTheResumedPhysicalSnapshot() throws Exception {
        String catalog = jdbc.queryForObject("SELECT DATABASE()", String.class);
        context.set(workspace.getId(), org.getId(), actor.getId(), "org_admin", catalog);
        Thread requestThread = Thread.currentThread();
        scope.withCatalog(catalog, () -> reads.withSnapshot(workspace.getId(), personId,
            List.of(workspace.getId()), disclosure -> {
                Connection original = DataSourceUtils.getConnection(dataSource);
                long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ, isolation(original));
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                disclosure.setGeneratedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
                var fingerprint = writer.measure(disclosure);
                assertEquals(2, fingerprint.noteCount());
                try (var executor = Executors.newSingleThreadExecutor()) {
                    executor.submit(() -> mutateOnIndependentConnection(connectionId)).get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("Concurrent committed mutation failed", exception);
                }
                audit.record(org.getId(), 1733, personId, workspace.getId(), fingerprint);
                assertSame(original, DataSourceUtils.getConnection(dataSource));
                assertEquals(connectionId, jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                assertEquals(catalog, context.getCatalog());
                assertEquals(requestThread, Thread.currentThread());
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(auditVisibleOn(original), "The old tenant snapshot must predate the control audit");
                try (Connection independent = dataSource.getConnection()) {
                    assertNotEquals(connectionId, physicalId(independent));
                    assertTrue(auditVisibleOn(independent), "The control audit must already be committed");
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                writer.write(disclosure, fingerprint, measured -> output);
                var notes = mapper.readTree(output.toByteArray()).path("notes");
                assertEquals(2, notes.size());
                assertEquals(updatedId, notes.get(0).path("id").asInt());
                assertEquals("before update", notes.get(0).path("content").asString());
                assertEquals(deletedId, notes.get(1).path("id").asInt());
                assertEquals("before delete", notes.get(1).path("content").asString());
                return null;
            }));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(List.of("after update", "after insert"), jdbc.queryForList(
            "SELECT content FROM note WHERE workspace_id = ? ORDER BY id", String.class, workspace.getId()));
    }

    private void mutateOnIndependentConnection(long snapshotConnectionId) {
        try (Connection connection = dataSource.getConnection()) {
            assertNotEquals(snapshotConnectionId, physicalId(connection));
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("UPDATE note SET content = 'after update' WHERE id = ?");
                    PreparedStatement delete = connection.prepareStatement("DELETE FROM note WHERE id = ?");
                    PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO note (workspace_id, person_id, author_id, content) VALUES (?, ?, ?, 'after insert')")) {
                update.setInt(1, updatedId);
                assertEquals(1, update.executeUpdate());
                delete.setInt(1, deletedId);
                assertEquals(1, delete.executeUpdate());
                insert.setInt(1, workspace.getId());
                insert.setInt(2, personId);
                insert.setInt(3, actor.getId());
                assertEquals(1, insert.executeUpdate());
                connection.commit();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean auditVisibleOn(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM audit_log WHERE org_id = ? AND action = 'appi.subject_request.disclosure'")) {
            statement.setInt(1, org.getId());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1) == 1;
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long physicalId(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static int isolation(Connection connection) {
        try {
            return connection.getTransactionIsolation();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int note(String content) {
        jdbc.update("INSERT INTO note (workspace_id, person_id, author_id, content) VALUES (?, ?, ?, ?)",
            workspace.getId(), personId, actor.getId(), content);
        return jdbc.queryForObject("SELECT MAX(id) FROM note WHERE workspace_id = ?", Integer.class, workspace.getId());
    }
}
