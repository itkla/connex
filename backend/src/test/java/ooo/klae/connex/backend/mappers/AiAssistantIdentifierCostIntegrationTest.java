package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantIdentifierResolver;
import ooo.klae.connex.backend.ai.assistant.AiAssistantSessionReadAudit;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.ReferenceService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import tools.jackson.databind.json.JsonMapper;

/** Reports real scan cost without shared-host wall-clock thresholds or candidate caps. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiAssistantIdentifierCostIntegrationTest extends AbstractMapperTest {
    @Autowired private AiAssistantIdentifierMapper identifiers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionFactory sessions;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private AiChatPrivacyGuard privacyGuard;
    @Autowired private AttachmentMapper attachments;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private OrganizationWorkspaceScopeControlAccess scope;
    @Autowired private ReferenceService references;
    private User user;
    private Integer sessionId;
    private boolean ownsWorkspace;

    @BeforeEach
    void isolatedWorkspace() {
        Workspace created = new Workspace();
        created.setName("identifier-cost-" + unique());
        created.setSlug("identifier-cost-" + unique());
        workspaceMapper.insert(created);
        TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, created.getId());
        workspace = created;
        ownsWorkspace = true;
        user = newUser();
        jdbc.update("UPDATE workspace_member SET role = 'owner' WHERE workspace_id = ? AND user_id = ?",
                workspace.getId(), user.getId());
        jdbc.update("INSERT INTO ai_chat_session (workspace_id, created_by_user_id, title, organization_privacy_generation) VALUES (?, ?, 'Cost fixture', 1)",
                workspace.getId(), user.getId());
        sessionId = jdbc.queryForObject("SELECT id FROM ai_chat_session WHERE workspace_id = ?", Integer.class, workspace.getId());
    }

    @AfterEach
    void cleanCommittedFixture() {
        if (!ownsWorkspace) {
            return;
        }
        jdbc.update("DELETE FROM ai_chat_session WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
        TestEntityFixtures.deleteChatPrivacy(jdbc, workspace.getId());
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        if (user != null) {
            jdbc.update("DELETE FROM app_user WHERE id = ?", user.getId());
        }
    }

    @Test
    void fiftyThousandJapaneseNamesReturnOnlyMentionsInOnePageAndReleaseQueueRootLocks() throws Exception {
        int inserted = 0;
        for (int population : List.of(200, 5_000, 50_200)) {
            while (inserted < population) {
                int end = Math.min(inserted + 500, population);
                List<Person> batch = new ArrayList<>();
                for (int index = inserted; index < end; index++) {
                    Person person = new Person();
                    person.setWorkspaceId(workspace.getId());
                    person.setName("東京商事営業所%06d".formatted(index));
                    batch.add(person);
                }
                personMapper.insertBatch(batch);
                inserted = end;
            }
            for (String text : List.of("No matching records", "今日の予定は？", "日本語のメモ ".repeat(2_286).substring(0, 16_000))) {
                measure(population, text, 0);
            }
        }
        Person mentioned = new Person();
        mentioned.setWorkspaceId(workspace.getId());
        mentioned.setName("㍿ Team");
        personMapper.insert(mentioned);
        measure(50_201, "Ask 株式会社 Team today.", 1);
        measure(50_201, "今日は㍿ Teamと会う", 1);
        Person duplicate = new Person();
        duplicate.setWorkspaceId(workspace.getId());
        duplicate.setName("株式会社 Ｔｅａｍ");
        personMapper.insert(duplicate);
        measure(50_202, "Ask ㍿ Team today.", 2);
        for (String text : List.of("No matching records", "Ask 株式会社 Team today.")) {
            explain(text);
        }
    }

    private void measure(int population, String text, int expected) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger returned = new AtomicInteger();
        var counted = mock(AiAssistantIdentifierMapper.class, delegatesTo(identifiers));
        doAnswer(call -> {
            calls.incrementAndGet();
            var rows = identifiers.findMentionedRecords(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6));
            returned.addAndGet(rows.size());
            return rows;
        }).when(counted).findMentionedRecords(anyInt(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyInt());
        var currentWorkspace = mock(WorkspaceService.class, delegatesTo(workspaceService));
        doReturn(workspace.getId()).when(currentWorkspace).getCurrentWorkspaceId();
        doReturn(user.getId()).when(currentWorkspace).getCurrentUserId();
        var resolver = new AiAssistantIdentifierResolver(counted, currentWorkspace, scope, references);
        var timedResolver = mock(AiAssistantIdentifierResolver.class, delegatesTo(resolver));
        var timedChat = mock(AiChatMapper.class, delegatesTo(chatMapper));
        var governance = mock(AiWorkspaceGovernanceService.class);
        when(governance.isEnabled(workspace.getId())).thenReturn(true);
        var queue = new AiChatTurnPersistenceService(privacyGuard, timedChat, attachments, currentWorkspace,
                new AiRestrictionEpoch(), governance, timedResolver, mock(AiAssistantToolExecutor.class),
                mock(AiAssistantSessionReadAudit.class), Clock.systemUTC(), mock(AiChatRealtimeDispatcher.class),
                JsonMapper.builder().build(), mock(AiRunLeaseService.class), ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered());
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        long[] nanos = new long[8];
        jdbc.update("DELETE FROM ai_chat_turn WHERE workspace_id = ? AND session_id = ?", workspace.getId(), sessionId);
        jdbc.update("DELETE FROM ai_chat_message WHERE workspace_id = ? AND session_id = ?", workspace.getId(), sessionId);
        doAnswer(call -> {
            long waitStarted = System.nanoTime();
            workspaceService.lockAndRequireMember(workspace.getId(), user.getId());
            nanos[0] = System.nanoTime() - waitStarted;
            nanos[1] = System.nanoTime();
            return null;
        }).when(currentWorkspace).lockAndRequireMember(workspace.getId(), user.getId());
        doAnswer(call -> {
            long waitStarted = System.nanoTime();
            var session = chatMapper.getSessionByIdForUpdate(workspace.getId(), user.getId(), sessionId);
            nanos[3] = System.nanoTime() - waitStarted;
            nanos[4] = System.nanoTime();
            return session;
        }).when(timedChat).getSessionByIdForUpdate(workspace.getId(), user.getId(), sessionId);
        try (Connection contender = DriverManager.getConnection(System.getenv("CONNEX_DB_URL"),
                System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"))) {
            contender.setAutoCommit(false);
            doAnswer(call -> {
                SQLException membershipContended = assertThrows(SQLException.class, () -> lockMembership(contender));
                assertEquals(3572, membershipContended.getErrorCode(), "Second connection must observe the held membership lock");
                SQLException sessionContended = assertThrows(SQLException.class, () -> lockSession(contender));
                assertEquals(3572, sessionContended.getErrorCode(), "Second connection must observe the held session lock");
                long started = System.nanoTime();
                var resolution = resolver.resolve(call.getArgument(0));
                nanos[2] = System.nanoTime() - started;
                assertEquals(expected, resolution.identifiers().size());
                return resolution;
            }).when(timedResolver).resolve(anyString());
            long queueStarted = System.nanoTime();
            var queued = transaction.execute(status -> queue.queue(sessionId, new AiChatTurnCreateRequest(text, List.of()), 0));
            long committed = System.nanoTime();
            nanos[5] = committed - nanos[1];
            nanos[6] = committed - nanos[4];
            nanos[7] = committed - queueStarted;
            assertTrue(queued != null);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_turn WHERE workspace_id = ? AND id = ? AND status = 'queued'",
                    Integer.class, workspace.getId(), queued.turnId()));
            assertEquals(text, jdbc.queryForObject("SELECT content FROM ai_chat_message WHERE workspace_id = ? AND id = ?",
                    String.class, workspace.getId(), queued.userMessageId()));
            lockMembership(contender);
            lockSession(contender);
            contender.rollback();
        }
        assertEquals(1, calls.get(), "Current keys must not paginate unrelated corpus rows");
        assertEquals(expected, returned.get());
        System.out.printf("IDENTIFIER_COST population=%d turnUtf16=%d calls=%d returned=%d resolverMs=%.3f membershipAcquireMs=%.3f sessionAcquireMs=%.3f membershipHoldMs=%.3f sessionHoldMs=%.3f queueMs=%.3f%n",
                population, text.length(), calls.get(), returned.get(), nanos[2] / 1e6, nanos[0] / 1e6,
                nanos[3] / 1e6, nanos[5] / 1e6, nanos[6] / 1e6, nanos[7] / 1e6);
    }

    private void lockMembership(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT user_id FROM workspace_member WHERE workspace_id = ? AND user_id = ? FOR UPDATE NOWAIT")) {
            statement.setInt(1, workspace.getId());
            statement.setInt(2, user.getId());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
            }
        }
    }

    private void lockSession(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM ai_chat_session WHERE workspace_id = ? AND id = ? FOR UPDATE NOWAIT")) {
            statement.setInt(1, workspace.getId());
            statement.setInt(2, sessionId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
            }
        }
    }

    private void explain(String text) throws Exception {
        var turn = MaskingEngine.mentionScanText(text);
        var parameters = new HashMap<String, Object>();
        parameters.put("workspaceId", workspace.getId());
        parameters.put("orgWorkspaceIdsJson", "[" + workspace.getId() + "]");
        parameters.put("literalText", turn.literalText());
        parameters.put("labelText", turn.labelText());
        parameters.put("limit", 200);
        parameters.put("afterKind", "");
        parameters.put("afterId", 0);
        var mapped = sessions.getConfiguration().getMappedStatement(AiAssistantIdentifierMapper.class.getName() + ".findMentionedRecords");
        var bound = mapped.getBoundSql(parameters);
        try (Connection connection = DriverManager.getConnection(System.getenv("CONNEX_DB_URL"),
                System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"));
                var statement = connection.prepareStatement("EXPLAIN ANALYZE FORMAT=TREE " + bound.getSql())) {
            new DefaultParameterHandler(mapped, parameters, bound).setParameters(statement);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                System.out.println("IDENTIFIER_EXPLAIN " + rows.getString(1));
            }
        }
    }
}
