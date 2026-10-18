package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.apache.ibatis.transaction.managed.ManagedTransaction;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.AiAssistantProposalSet;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

/** Exercises dormant storage without any proposal producer or decision service. */
class AiAssistantProposalSetMapperTest extends AbstractMapperTest {
    @Autowired private AiAssistantProposalSetMapper mapper;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private SqlSessionFactory sqlSessionFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void arrayOfOneAndScalarReloadTheirOriginalModeAndEnvelope() {
        AiAssistantProposalSet bulk = proposalSet(workspace, newUser(), "bulk", 1);
        AiAssistantProposalSet scalar = proposalSet(workspace, newUser(), "scalar", 1);
        insert(bulk);
        insert(scalar);
        AiChatToolCall bulkMember = member(bulk, 1);
        bulkMember.setArgumentsJson("{\"tier\":\"confirm\",\"pinned\":{\"tagId\":42}}");
        AiChatToolCall scalarMember = member(scalar, 1);
        scalarMember.setArgumentsJson("{\"tier\":\"auto\"}");
        assertEquals(1, mapper.insertMember(bulkMember));
        assertEquals(1, mapper.insertMember(scalarMember));
        assertTrue(bulkMember.getId() > 0);
        assertTrue(scalarMember.getId() > 0);
        sqlSession.clearCache();

        var configuration = sqlSessionFactory.getConfiguration();
        try (var freshSession = new DefaultSqlSession(configuration,
                configuration.newExecutor(new ManagedTransaction(sqlSession.getConnection(), false)), false)) {
            AiAssistantProposalSetMapper reloaded = freshSession.getMapper(AiAssistantProposalSetMapper.class);
            AiAssistantProposalSet storedBulk = reloaded.findByKey(
                    bulk.getWorkspaceId(), bulk.getSessionId(), bulk.getProposalKey());
            AiAssistantProposalSet storedScalar = reloaded.findByKey(
                    scalar.getWorkspaceId(), scalar.getSessionId(), scalar.getProposalKey());
            assertNotNull(storedBulk);
            assertNotNull(storedScalar);
            assertEquals("bulk", storedBulk.getInvocationMode());
            assertEquals(1, storedBulk.getBulkTurnSlot());
            assertEquals(1, storedBulk.getRowCount());
            assertEquals(bulk.getScopeRevision(), storedBulk.getScopeRevision());
            assertEquals(7, storedBulk.getPrivacyGeneration());
            assertEquals("scalar", storedScalar.getInvocationMode());
            assertNull(storedScalar.getBulkTurnSlot());
            List<AiChatToolCall> bulkRows = reloaded.listMembers(
                    bulk.getWorkspaceId(), bulk.getSessionId(), bulk.getProposalKey());
            List<AiChatToolCall> scalarRows = reloaded.listMembers(
                    scalar.getWorkspaceId(), scalar.getSessionId(), scalar.getProposalKey());
            assertEquals(1, bulkRows.size());
            assertEquals(1, scalarRows.size());
            AiChatToolCall storedMember = bulkRows.getFirst();
            assertEquals("bulk", storedMember.getInvocationMode());
            assertEquals("staged", storedMember.getPublicationState());
            assertEquals(storedBulk.getCreatedAt(), storedMember.getCreatedAt());
            assertTrue(storedMember.getProposalPreparedAt().startsWith("2000-01-01 00:00:00.123456"));
            assertTrue(storedMember.getArgumentsJson().contains("confirm"));
            assertTrue(storedMember.getArgumentsJson().contains("42"));
            assertTrue(scalarRows.getFirst().getArgumentsJson().contains("auto"));
            for (AiChatToolCall row : List.of(storedMember, scalarRows.getFirst())) {
                assertEquals("proposed", row.getStatus());
                assertNull(row.getExecutedAt());
                assertNull(row.getResultJson());
                assertTrue(row.isServerProposal());
            }
        }
    }

    @Test
    void identityAndAllReadsAreScopedByWorkspaceAndSession() {
        User actor = newUser();
        AiAssistantProposalSet first = proposalSet(workspace, actor, "bulk", 1);
        AiAssistantProposalSet foreign = proposalSet(newWorkspace(), actor, "bulk", 1);
        foreign.setProposalKey(first.getProposalKey());
        insert(first);
        insert(foreign);
        assertEquals(1, mapper.insertMember(member(first, 1)));
        assertEquals(1, mapper.insertMember(member(foreign, 1)));

        assertNull(mapper.findByKey(first.getWorkspaceId(), foreign.getSessionId(), first.getProposalKey()));
        assertNull(mapper.findByKey(foreign.getWorkspaceId(), first.getSessionId(), first.getProposalKey()));
        assertTrue(mapper.listMembers(first.getWorkspaceId(), foreign.getSessionId(), first.getProposalKey()).isEmpty());
        assertFalse(mapper.hasCompleteMembership(first.getWorkspaceId(), foreign.getSessionId(), first.getProposalKey()));
        AiAssistantProposalSet original = mapper.findByCallIdentity(first.getWorkspaceId(), first.getSessionId(),
                first.getOriginTurnId(), first.getStepNumber(), first.getCallOrdinal());
        assertNotNull(original);
        assertEquals(first.getProposalKey(), original.getProposalKey());
        assertEquals(first.getIntentFingerprint(), original.getIntentFingerprint());
        assertNull(mapper.findByCallIdentity(foreign.getWorkspaceId(), first.getSessionId(),
                first.getOriginTurnId(), first.getStepNumber(), first.getCallOrdinal()));
        assertNull(mapper.findByCallIdentity(first.getWorkspaceId(), foreign.getSessionId(),
                first.getOriginTurnId(), first.getStepNumber(), first.getCallOrdinal()));
        assertEquals(1, members(first).size());
        assertEquals(1, members(foreign).size());
    }

    @Test
    void exactMembershipIsCompleteOrderedAndIndependentOfOtherTranscriptRows() {
        AiAssistantProposalSet set = proposalSet(workspace, newUser(), "bulk", 20);
        insert(set);
        assertFalse(complete(set));
        for (int ordinal = 20; ordinal >= 1; ordinal--) {
            assertEquals(1, mapper.insertMember(member(set, ordinal)));
            assertEquals(ordinal == 1, complete(set));
        }
        for (int index = 0; index < 100; index++) {
            AiChatToolCall legacy = member(set, 1);
            legacy.setIdempotencyKey(UUID.randomUUID().toString());
            legacy.setStatus("failed");
            assertEquals(1, chatMapper.insertToolCall(legacy));
        }
        assertEquals(IntStream.rangeClosed(1, 20).boxed().toList(),
                members(set).stream().map(AiChatToolCall::getProposalOrdinal).toList());
        assertTrue(complete(set));
        assertThrows(DataAccessException.class, () -> mapper.insertMember(member(set, 1)));
        jdbc.update("DELETE FROM ai_chat_tool_call WHERE workspace_id = ? AND proposal_key = ? AND proposal_ordinal = 10",
                set.getWorkspaceId(), set.getProposalKey());
        sqlSession.clearCache();
        assertFalse(complete(set));
        assertEquals(1, mapper.insertMember(member(set, 10)));
        assertTrue(complete(set));
        jdbc.update("UPDATE ai_chat_tool_call SET tool_name = 'remove_tag' WHERE workspace_id = ? AND proposal_key = ? AND proposal_ordinal = 10",
                set.getWorkspaceId(), set.getProposalKey());
        sqlSession.clearCache();
        assertFalse(complete(set));
    }

    @Test
    void aGapWithTheRightCountAndDifferentCreationTimeAreIncomplete() {
        AiAssistantProposalSet set = proposalSet(workspace, newUser(), "bulk", 2);
        insert(set);
        assertEquals(1, mapper.insertMember(member(set, 1)));
        assertEquals(1, mapper.insertMember(member(set, 2)));
        jdbc.update("UPDATE ai_chat_tool_call SET proposal_ordinal = 3 WHERE workspace_id = ? AND proposal_key = ? AND proposal_ordinal = 2",
                set.getWorkspaceId(), set.getProposalKey());
        sqlSession.clearCache();
        assertFalse(complete(set));
        jdbc.update("UPDATE ai_chat_tool_call SET proposal_ordinal = 2, created_at = DATE_ADD(created_at, INTERVAL 1 MICROSECOND) WHERE workspace_id = ? AND proposal_key = ? AND proposal_ordinal = 3",
                set.getWorkspaceId(), set.getProposalKey());
        sqlSession.clearCache();
        assertFalse(complete(set));
    }

    @Test
    void failureAtEveryChildPositionRollsBackTheWholeStorageTransaction() {
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        for (int fault = 1; fault <= 20; fault++) {
            AiAssistantProposalSet set = proposalSet(workspace, newUser(), "bulk", 20);
            int faultOrdinal = fault;
            assertThrows(IllegalStateException.class, () -> nested.executeWithoutResult(status -> {
                insert(set);
                for (int ordinal = 1; ordinal <= 20; ordinal++) {
                    AiChatToolCall row = member(set, ordinal);
                    if (ordinal == faultOrdinal) {
                        row.setProposalOrdinal(21);
                    }
                    if (mapper.insertMember(row) != 1) {
                        throw new IllegalStateException("Incomplete test proposal set");
                    }
                }
            }));
            sqlSession.clearCache();
            assertNull(mapper.findByKey(set.getWorkspaceId(), set.getSessionId(), set.getProposalKey()));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_chat_tool_call WHERE workspace_id = ? AND proposal_key = ?",
                    Integer.class, set.getWorkspaceId(), set.getProposalKey()));
        }
    }

    @Test
    void callIdentityAndFailedBulkSlotCannotBeReused() {
        AiAssistantProposalSet set = proposalSet(workspace, newUser(), "bulk", 1);
        insert(set);
        set.setProposalKey(UUID.randomUUID().toString());
        set.setIntentFingerprint("b".repeat(64));
        assertThrows(DataAccessException.class, () -> mapper.insert(set));
        set.setStepNumber(2);
        assertThrows(DataAccessException.class, () -> mapper.insert(set));
        jdbc.update("UPDATE ai_assistant_proposal_set SET publication_state = 'failed', failure_reason = 'call_failed' WHERE workspace_id = ? AND origin_turn_id = ?",
                set.getWorkspaceId(), set.getOriginTurnId());
        assertThrows(DataAccessException.class, () -> mapper.insert(set));
        set.setInvocationMode("scalar");
        insert(set);
        set.setProposalKey(UUID.randomUUID().toString());
        set.setStepNumber(3);
        insert(set);
    }

    @Test
    void parentChecksRefuseInvalidModesCountsCallCoordinatesAndPublicationStates() {
        for (Consumer<AiAssistantProposalSet> corrupt : List.<Consumer<AiAssistantProposalSet>>of(
                set -> set.setInvocationMode("unknown"),
                set -> set.setInvocationMode(null),
                set -> set.setRowCount(0),
                set -> set.setRowCount(21),
                set -> { set.setInvocationMode("scalar"); set.setRowCount(2); },
                set -> set.setStepNumber(0),
                set -> set.setStepNumber(65),
                set -> set.setCallOrdinal(-1),
                set -> set.setCallOrdinal(5),
                set -> set.setScopeRevision(""),
                set -> set.setIntentFingerprint("short"),
                set -> set.setPrivacyGeneration(-1))) {
            AiAssistantProposalSet set = proposalSet(workspace, newUser(), "bulk", 1);
            corrupt.accept(set);
            assertThrows(DataAccessException.class, () -> mapper.insert(set));
        }
        AiAssistantProposalSet set = proposalSet(workspace, newUser(), "scalar", 1);
        insert(set);
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE ai_assistant_proposal_set SET publication_state = 'failed' WHERE workspace_id = ? AND proposal_key = ?",
                set.getWorkspaceId(), set.getProposalKey()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE ai_assistant_proposal_set SET publication_state = 'failed', failure_reason = 'exception text' WHERE workspace_id = ? AND proposal_key = ?",
                set.getWorkspaceId(), set.getProposalKey()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE ai_assistant_proposal_set SET publication_state = 'expired' WHERE workspace_id = ? AND proposal_key = ?",
                set.getWorkspaceId(), set.getProposalKey()));
    }

    @Test
    void parentInsertRequiresMatchingRequesterAndRealUserMessageAncestry() {
        AiAssistantProposalSet set = proposalSet(workspace, newUser(), "scalar", 1);
        AiAssistantProposalSet other = proposalSet(workspace, newUser(), "scalar", 1);
        int turn = set.getOriginTurnId();
        set.setOriginTurnId(other.getOriginTurnId());
        assertEquals(0, mapper.insert(set));
        set.setOriginTurnId(turn);
        Integer requester = set.getRequestedByUserId();
        set.setRequestedByUserId(other.getRequestedByUserId());
        assertEquals(0, mapper.insert(set));
        set.setRequestedByUserId(null);
        assertEquals(0, mapper.insert(set));
        set.setRequestedByUserId(requester);
        jdbc.update("UPDATE ai_chat_message SET author_kind = 'assistant' WHERE workspace_id = ? AND id = ?",
                set.getWorkspaceId(), set.getMessageId());
        assertEquals(0, mapper.insert(set));
    }

    @Test
    void memberInsertRefusesInconsistentInputsAndSettledParents() {
        AiAssistantProposalSet set = proposalSet(workspace, newUser(), "scalar", 1);
        insert(set);
        for (Consumer<AiChatToolCall> corrupt : List.<Consumer<AiChatToolCall>>of(
                row -> row.setWorkspaceId(row.getWorkspaceId() + 100000),
                row -> row.setSessionId(row.getSessionId() + 100000),
                row -> row.setMessageId(row.getMessageId() + 100000),
                row -> row.setRequestedByUserId(null),
                row -> row.setProposalKey("absent"),
                row -> row.setProposalOrdinal(2),
                row -> row.setServerProposal(false),
                row -> row.setToolName("remove_tag"),
                row -> row.setProposalPreparedAt(null),
                row -> row.setProposalPreparedAt("2099-01-01 00:00:00"),
                row -> row.setIdempotencyKey(null))) {
            AiChatToolCall row = member(set, 1);
            corrupt.accept(row);
            assertEquals(0, mapper.insertMember(row));
        }
        assertTrue(members(set).isEmpty());
        assertEquals(1, mapper.insertMember(member(set, 1)));
        for (String state : List.of("ready", "failed")) {
            jdbc.update("UPDATE ai_assistant_proposal_set SET publication_state = ?, failure_reason = ? WHERE workspace_id = ? AND proposal_key = ?",
                    state, state.equals("failed") ? "call_failed" : null,
                    set.getWorkspaceId(), set.getProposalKey());
            assertEquals(0, mapper.insertMember(member(set, 1)));
            sqlSession.clearCache();
            AiAssistantProposalSet reloaded = mapper.findByKey(set.getWorkspaceId(), set.getSessionId(), set.getProposalKey());
            assertNotNull(reloaded);
            assertEquals(state, reloaded.getPublicationState());
            assertEquals(state.equals("failed") ? "call_failed" : null, reloaded.getFailureReason());
            assertEquals(state, members(set).getFirst().getPublicationState());
            assertEquals("scalar", members(set).getFirst().getInvocationMode());
        }
    }

    @Test
    void foreignKeysRejectCrossWorkspaceSessionMessageAndMissingParents() {
        User actor = newUser();
        AiAssistantProposalSet first = proposalSet(workspace, actor, "scalar", 1);
        AiAssistantProposalSet otherSession = proposalSet(workspace, actor, "scalar", 1);
        AiAssistantProposalSet otherWorkspace = proposalSet(newWorkspace(), actor, "scalar", 1);
        insert(first);
        insert(otherSession);
        insert(otherWorkspace);
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE ai_assistant_proposal_set SET origin_turn_id = ? WHERE workspace_id = ? AND proposal_key = ?",
                otherSession.getOriginTurnId(), first.getWorkspaceId(), first.getProposalKey()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE ai_assistant_proposal_set SET message_id = ? WHERE workspace_id = ? AND proposal_key = ?",
                otherWorkspace.getMessageId(), first.getWorkspaceId(), first.getProposalKey()));
        assertEquals(1, mapper.insertMember(member(first, 1)));
        for (int messageId : List.of(otherSession.getMessageId(), otherWorkspace.getMessageId())) {
            assertThrows(DataAccessException.class, () -> jdbc.update(
                    "UPDATE ai_chat_tool_call SET message_id = ? WHERE workspace_id = ? AND proposal_key = ?",
                    messageId, first.getWorkspaceId(), first.getProposalKey()));
        }
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE ai_chat_tool_call SET proposal_key = 'absent' WHERE workspace_id = ? AND proposal_key = ?",
                first.getWorkspaceId(), first.getProposalKey()));
        for (String assignment : List.of("proposal_key = NULL", "proposal_ordinal = NULL",
                "proposal_prepared_at = NULL", "proposal_ordinal = 0", "proposal_ordinal = 21",
                "server_proposal = FALSE", "message_id = NULL")) {
            assertThrows(DataAccessException.class, () -> jdbc.update(
                    "UPDATE ai_chat_tool_call SET " + assignment + " WHERE workspace_id = ? AND proposal_key = ?",
                    first.getWorkspaceId(), first.getProposalKey()));
        }
    }

    private void insert(AiAssistantProposalSet set) {
        assertEquals(1, mapper.insert(set));
    }

    private List<AiChatToolCall> members(AiAssistantProposalSet set) {
        return mapper.listMembers(set.getWorkspaceId(), set.getSessionId(), set.getProposalKey());
    }

    private boolean complete(AiAssistantProposalSet set) {
        return mapper.hasCompleteMembership(set.getWorkspaceId(), set.getSessionId(), set.getProposalKey());
    }

    private Workspace newWorkspace() {
        Workspace scope = new Workspace();
        scope.setName("Proposal storage " + unique());
        scope.setSlug("proposal-storage-" + unique());
        workspaceMapper.insert(scope);
        return scope;
    }

    private AiAssistantProposalSet proposalSet(Workspace scope, User actor, String mode, int count) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(scope.getId());
        session.setCreatedByUserId(actor.getId());
        session.setTitle("Storage fixture");
        session.setVisibility("private");
        session.setStatus("active");
        assertEquals(1, chatMapper.insertSession(session));
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(scope.getId());
        message.setSessionId(session.getId());
        message.setSeq(1);
        message.setAuthorKind("user");
        message.setAuthorUserId(actor.getId());
        message.setContent("Storage fixture");
        assertEquals(1, chatMapper.insertMessage(message));
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(scope.getId());
        turn.setSessionId(session.getId());
        turn.setRequestedByUserId(actor.getId());
        turn.setStatus("running");
        turn.setPrivacyMode("masked");
        assertEquals(1, chatMapper.insertTurn(turn));
        AiAssistantProposalSet set = new AiAssistantProposalSet();
        set.setWorkspaceId(scope.getId());
        set.setProposalKey(UUID.randomUUID().toString());
        set.setSessionId(session.getId());
        set.setMessageId(message.getId());
        set.setOriginTurnId(turn.getId());
        set.setRequestedByUserId(actor.getId());
        set.setStepNumber(1);
        set.setCallOrdinal(0);
        set.setToolName("add_tag");
        set.setInvocationMode(mode);
        set.setRowCount(count);
        set.setIntentFingerprint("a".repeat(64));
        set.setScopeRevision(UUID.randomUUID().toString());
        set.setPrivacyGeneration(7);
        return set;
    }

    private AiChatToolCall member(AiAssistantProposalSet set, int ordinal) {
        AiChatToolCall row = new AiChatToolCall();
        row.setWorkspaceId(set.getWorkspaceId());
        row.setSessionId(set.getSessionId());
        row.setMessageId(set.getMessageId());
        row.setRequestedByUserId(set.getRequestedByUserId());
        row.setToolName(set.getToolName());
        row.setProposalKey(set.getProposalKey());
        row.setProposalOrdinal(ordinal);
        row.setProposalPreparedAt("2000-01-01 00:00:00.123456");
        row.setServerProposal(true);
        row.setIdempotencyKey(UUID.randomUUID().toString());
        row.setArgumentsJson("{\"tier\":\"confirm\"}");
        return row;
    }
}
