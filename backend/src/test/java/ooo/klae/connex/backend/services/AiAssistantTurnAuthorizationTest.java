package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantTurnService;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AiAssistantTurnAuthorizationTest extends AbstractServiceTest {
    private static final String INACCESSIBLE = "AI assistant session is not accessible";

    @Autowired private AiAssistantTurnService turnService;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private RoleMapper roleMapper;
    @MockitoBean private AiFeatureGate featureGate;
    @MockitoBean private AiGenerationService generationService;

    @Test
    void missingAiUseCreatesNoTurnOrMessageAndNeverTouchesGeneration() {
        AiChatSession session = session(currentUser, workspace);
        WorkspaceRole revoked = new WorkspaceRole();
        revoked.setWorkspaceId(workspace.getId());
        revoked.setName("No AI " + unique());
        roleMapper.insertRole(revoked);
        workspaceMapper.setMemberCustomRole(workspace.getId(), currentUser.getId(), revoked.getId());

        assertThrows(ForbiddenException.class, () ->
                turnService.start(session.getId(), request("Blocked")));

        assertEquals(0, chatMapper.countMessages(workspace.getId(), session.getId()));
        assertEquals(0, chatMapper.countActiveTurns(workspace.getId(), session.getId()));
        verifyNoInteractions(generationService);
    }

    @Test
    void otherTenantCallerGetsTheSameGenericInaccessibleFailureBeforeGeneration() {
        AiChatSession session = session(currentUser, workspace);
        User caller = newUser();
        Workspace other = new Workspace();
        other.setName("Other " + unique());
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        workspaceMapper.addMember(other.getId(), caller.getId(), "owner");
        authenticateAs(caller, other.getId());

        ResourceNotFoundException inaccessible = assertThrows(
                ResourceNotFoundException.class,
                () -> turnService.start(session.getId(), request("Cross tenant")));

        assertEquals(INACCESSIBLE, inaccessible.getMessage());
        verifyNoInteractions(generationService);
    }

    @Test
    void privateSessionNonParticipantGetsTheSameGenericInaccessibleFailure() {
        AiChatSession session = session(currentUser, workspace);
        User caller = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), caller.getId(), "admin");
        authenticateAs(caller, workspace.getId());

        ResourceNotFoundException inaccessible = assertThrows(
                ResourceNotFoundException.class,
                () -> turnService.start(session.getId(), request("Private")));

        assertEquals(INACCESSIBLE, inaccessible.getMessage());
        verifyNoInteractions(generationService);
    }

    @Test
    void adminParticipantCannotStartATurnOnARetainedSession() {
        User author = newUser();
        AiChatSession session = session(author, workspace);
        chatMapper.insertParticipant(workspace.getId(), session.getId(), currentUser.getId());
        workspaceMapper.removeMember(workspace.getId(), author.getId());

        ResourceNotFoundException inaccessible = assertThrows(
            ResourceNotFoundException.class,
            () -> turnService.start(session.getId(), request("Retained")));

        assertEquals(INACCESSIBLE, inaccessible.getMessage());
        assertEquals(0, chatMapper.countMessages(workspace.getId(), session.getId()));
        assertEquals(0, chatMapper.countActiveTurns(workspace.getId(), session.getId()));
        verifyNoInteractions(generationService);
    }

    @Test
    void archivedOrAlreadyRunningSessionReturnsConflictWithoutGeneration() {
        AiChatSession archived = session(currentUser, workspace);
        chatMapper.updateSession(workspace.getId(), archived.getId(), null, "archived");
        assertThrows(ConflictException.class, () ->
                turnService.start(archived.getId(), request("Archived")));

        AiChatSession active = session(currentUser, workspace);
        AiChatTurn running = new AiChatTurn();
        running.setWorkspaceId(workspace.getId());
        running.setSessionId(active.getId());
        running.setRequestedByUserId(currentUser.getId());
        running.setStatus("running");
        chatMapper.insertTurn(running);
        assertThrows(ConflictException.class, () ->
                turnService.start(active.getId(), request("Concurrent")));

        verifyNoInteractions(generationService);
    }

    private AiChatSession session(User owner, Workspace owningWorkspace) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(owningWorkspace.getId());
        session.setCreatedByUserId(owner.getId());
        session.setTitle("Turn " + unique());
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private static AiChatTurnCreateRequest request(String content) {
        return new AiChatTurnCreateRequest(content, List.of());
    }
}
