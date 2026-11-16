package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.AiChatMapper;

/** Replayed trajectory steps retain their committed execution's organization provenance. */
class AssistantTrajectoryFixtureTest {
    private final AbstractScriptedTrajectoryTest fixture = new AbstractScriptedTrajectoryTest() {};
    private final AiAssistantPrivacyService authority = mock(AiAssistantPrivacyService.class);
    private final AiChatMapper mapper = mock(AiChatMapper.class);
    private final DuplicateKeyException duplicate = new DuplicateKeyException("fixture call already exists");
    private AiChatQueuedTurn replay;

    @BeforeEach
    void prepareCommittedTurn() {
        var organization = new Organization();
        organization.setId(29);
        var workspace = new Workspace();
        workspace.setId(7);
        workspace.setOrgId(29);
        var member = new User();
        member.setId(11);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(7))).thenReturn(Map.of(
                "id", 17, "session_id", 13, "privacy_generation", 4L,
                "organization_privacy_generation", 7L));
        when(jdbc.queryForObject(contains("SELECT message_id"), eq(Integer.class), eq(7))).thenReturn(19);
        when(jdbc.queryForObject(contains("SELECT seq"), eq(Integer.class), eq(7), eq(19))).thenReturn(1);
        var persistence = mock(AiChatTurnPersistenceService.class);
        var guard = new AiChatPrivacyGuard(authority);
        when(persistence.proposeTool(any(), eq(2), eq(1), eq("search_records"), eq("{}")))
                .thenAnswer(call -> {
                    replay = call.getArgument(0);
                    guard.requireCurrent(mapper, replay.privacyToken());
                    throw duplicate;
                });
        ReflectionTestUtils.setField(fixture, "organization", organization);
        ReflectionTestUtils.setField(fixture, "workspace", workspace);
        ReflectionTestUtils.setField(fixture, "member", member);
        ReflectionTestUtils.setField(fixture, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(fixture, "persistenceService", persistence);
        ReflectionTestUtils.setField(fixture, "restrictionEpoch", mock(AiRestrictionEpoch.class));
        var session = new AiChatSession();
        session.setWorkspaceId(7);
        session.setId(13);
        session.setOrganizationPrivacyGeneration(7L);
        var barrier = new AiAgentPrivacyBarrier();
        barrier.setWorkspaceId(7);
        barrier.setGeneration(4);
        when(authority.organizationId(7)).thenReturn(29);
        when(authority.current(29)).thenReturn(7L);
        when(mapper.getPrivacySession(7, 13)).thenReturn(session);
        when(mapper.getPrivacyBarrier(7)).thenReturn(barrier);
    }

    @Test
    void currentOrganizationReplayReachesTheDuplicateKeyConstraint() {
        assertSame(duplicate, fixture.proposeReadAgain(2, 1));
        assertEquals(new AiChatPrivacyToken(7, 13, 4, null, 29, 7), replay.privacyToken());
    }

    @Test
    void invalidatedOrganizationReplayCannotBorrowCurrentAuthority() {
        when(authority.current(29)).thenReturn(8L);

        var failure = assertInstanceOf(AiAssistantLoopException.class, fixture.proposeReadAgain(2, 1));

        assertEquals("restrictions_changed", failure.detailReason());
        assertEquals(new AiChatPrivacyToken(7, 13, 4, null, 29, 7), replay.privacyToken());
    }
}
