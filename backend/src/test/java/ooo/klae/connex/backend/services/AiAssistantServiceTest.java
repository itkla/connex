package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCallReadService;
import ooo.klae.connex.backend.ai.assistant.AiChatAttachmentService;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatParticipant;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
import ooo.klae.connex.backend.dto.AiChatSessionCreateRequest;
import ooo.klae.connex.backend.dto.AiChatSessionDetailDto;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.dto.AiChatSessionUpdateRequest;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

class AiAssistantServiceTest extends AbstractServiceTest {

    private static final String INACCESSIBLE = "AI assistant session is not accessible";

    @Autowired private AiAssistantService service;
    @Autowired private AiAssistantToolCallReadService toolCallReadService;
    @Autowired private AiChatAttachmentService attachmentService;
    @Autowired private AiChatTurnPersistenceService turnPersistenceService;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private WorkspaceService workspaceService;
    @MockitoSpyBean private AiChatRealtimeDispatcher realtimeDispatcher;

    @Test
    void createDefaultsToPrivateActiveAndOwnerCanRead() {
        AiChatSessionDto created = service.create(createRequest("  Planning room  "));
        AiChatSession persisted = chatMapper.getSessionById(
            workspace.getId(), currentUser.getId(), created.getId());
        AiChatSessionDetailDto detail = service.get(created.getId(), 1, 50);

        assertNotEquals(0, created.getId());
        assertEquals("Planning room", created.getTitle());
        assertEquals("private", created.getVisibility());
        assertEquals("active", created.getStatus());
        assertFalse(created.isArchived());
        assertTrue(created.isOwnedByCurrentUser());
        assertNotNull(persisted);
        assertEquals("active", persisted.getStatus());
        assertEquals(created.getId(), detail.session().getId());
        assertTrue(detail.messages().items().isEmpty());
        assertEquals(0, detail.messages().total());
    }

    @Test
    void durableHistorySummaryIsQueryableButProjectsOnlyTranscriptMarker() {
        AiChatSession session = privateSession(currentUser, "Compacted history");
        AiChatMessage summary = new AiChatMessage();
        summary.setWorkspaceId(workspace.getId());
        summary.setSessionId(session.getId());
        summary.setSeq(1);
        summary.setAuthorKind("system");
        summary.setContent("Server-only early relationship facts");
        summary.setStructuredJson(
                "{\"kind\":\"history_summary\",\"sourceFromSeq\":1,"
                        + "\"throughSeq\":4,\"resources\":[]}");
        chatMapper.insertMessage(summary);
        assistantMessage(session.getId(), 2, "Visible recent answer", "{\"turnId\":1}");

        AiChatSessionDetailDto detail = service.get(session.getId(), 1, 50);

        assertTrue(detail.session().isHistorySummarized());
        assertTrue(detail.messages().items().getFirst().isHistorySummarized());
        assertEquals("", detail.messages().items().getFirst().getContent());
        assertEquals(
                "Server-only early relationship facts",
                chatMapper.getHistorySummary(workspace.getId(), session.getId()).getContent());
        assertEquals(
                List.of(2),
                chatMapper.listMessagesForCompaction(
                        workspace.getId(), session.getId(), 0, 3, 10).stream()
                        .map(AiChatMessage::getSeq)
                        .toList());
    }

    @Test
    void sessionCreationExplicitlyControlsAutomaticTitleEligibility() {
        AiChatSessionCreateRequest automaticRequest = createRequest("New conversation");
        automaticRequest.setAutoTitle(true);

        AiChatSessionDto automatic = service.create(automaticRequest);
        AiChatSessionDto manual = service.create(createRequest("Planning room"));

        AiChatSession automaticStored = chatMapper.getSessionById(
                workspace.getId(), currentUser.getId(), automatic.getId());
        AiChatSession manualStored = chatMapper.getSessionById(
                workspace.getId(), currentUser.getId(), manual.getId());
        assertNotNull(automaticStored);
        assertNotNull(manualStored);
        assertFalse(automaticStored.isTitleUserSet());
        assertTrue(manualStored.isTitleUserSet());
    }

    @Test
    void sharedParticipantCanListReadAndAppend() {
        AiChatSession session = sharedSession(currentUser, "Shared room");
        User participant = aiUser("admin");
        chatMapper.insertParticipant(workspace.getId(), session.getId(), participant.getId());
        authenticateAs(participant, workspace.getId());

        AiChatMessageDto appended = service.appendMessage(
            session.getId(), messageRequest("Participant message"));
        AiChatSessionDetailDto detail = service.get(session.getId(), 1, 50);

        assertEquals(1, service.page(1, 25).total());
        assertEquals("user", appended.getAuthorKind());
        assertEquals(participant.getId(), appended.getAuthorUserId());
        assertEquals(1, appended.getSeq());
        assertEquals("Participant message", detail.messages().items().getFirst().getContent());
        assertFalse(detail.session().isOwnedByCurrentUser());
        assertEquals(2, auditLogMapper.findByEntity(
            workspace.getId(), "ai_chat_session", session.getId(), 10, 0).size());
    }

    /**
     * Every disclosed surface shares the same metadata-only accountability record. This route
     * matrix makes deleting any one choke-point call observable instead of allowing neighboring
     * routes to keep a broad audit assertion green.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "page",
        "get",
        "invitations",
        "participants",
        "presence",
        "attachments",
        "turn",
        "toolCalls",
        "toolCall"
    })
    void everyAccessibleSessionDisclosureByAnAdminRecordsExactlyOneMetadataOnlyRow(String route) {
        String sessionTitle = "caller-text-must-not-be-audited-" + unique();
        AiChatSession session = sharedSession(currentUser, sessionTitle);
        User admin = aiUser("admin");
        int toolCallId = prepareAdministrativeRead(route, session, admin);
        authenticateAs(admin, workspace.getId());
        assertEquals(admin, SecurityContextHolder.getContext().getAuthentication().getPrincipal());

        invokeAdministrativeRead(route, session, toolCallId);

        List<AuditLog> records = auditLogMapper.findByEntity(
            workspace.getId(), "ai_chat_session", session.getId(), 10, 0);
        assertEquals(1, records.size());
        AuditLog record = records.getFirst();
        assertEquals("ai.assistant.session.read", record.getAction());
        assertEquals(admin.getId(), record.getActorId());
        assertEquals("{\"scope\": \"accessible\"}", record.getChanges());
        assertEquals("Assistant session " + session.getId(), record.getTargetLabel());
        assertEquals("Administrative assistant session read", record.getSummary());
        assertFalse(record.getTargetLabel().contains(sessionTitle));
        assertFalse(record.getChanges().contains(sessionTitle));
    }

    @Test
    void accessibleSessionReadsWithoutAdministrativePermissionProduceNoAuditRow() {
        AiChatSession session = sharedSession(currentUser, "Ordinary shared read");
        User member = newUser();
        WorkspaceRole aiUseOnly = customRole(
                "AI use without oversight", List.of(Permission.AI_USE.name()));
        workspaceMapper.setMemberCustomRole(
                workspace.getId(), member.getId(), aiUseOnly.getId());
        chatMapper.insertParticipant(workspace.getId(), session.getId(), member.getId());
        authenticateAs(member, workspace.getId());
        assertEquals(member, SecurityContextHolder.getContext().getAuthentication().getPrincipal());

        service.get(session.getId(), 1, 50);

        assertTrue(auditLogMapper.findByEntity(
                workspace.getId(), "ai_chat_session", session.getId(), 10, 0).isEmpty());
    }

    @Test
    void administratorsReadingTheirOwnSessionProduceNoAuditRow() {
        User admin = aiUser("admin");
        AiChatSession session = sharedSession(admin, "Administrator-owned session");
        authenticateAs(admin, workspace.getId());
        assertEquals(admin, SecurityContextHolder.getContext().getAuthentication().getPrincipal());

        service.get(session.getId(), 1, 50);

        assertTrue(auditLogMapper.findByEntity(
                workspace.getId(), "ai_chat_session", session.getId(), 10, 0).isEmpty());
    }

    @Test
    void sameWorkspaceNonParticipantAndOtherTenantUseIdenticalForbiddenMessage() {
        AiChatSession session = sharedSession(currentUser, "Protected room");
        User nonParticipant = aiUser("admin");
        authenticateAs(nonParticipant, workspace.getId());
        ForbiddenException sameWorkspace = assertThrows(
            ForbiddenException.class,
            () -> service.get(session.getId(), 1, 50));

        Workspace other = newWorkspace();
        workspaceMapper.addMember(other.getId(), nonParticipant.getId(), "admin");
        authenticateAs(nonParticipant, other.getId());
        ForbiddenException otherTenant = assertThrows(
            ForbiddenException.class,
            () -> service.get(session.getId(), 1, 50));
        ForbiddenException unknown = assertThrows(
            ForbiddenException.class,
            () -> service.get(Integer.MAX_VALUE, 1, 50));

        assertEquals(INACCESSIBLE, sameWorkspace.getMessage());
        assertEquals(INACCESSIBLE, otherTenant.getMessage());
        assertEquals(INACCESSIBLE, unknown.getMessage());
    }

    @Test
    void retainedScopeNeverExposesAnActiveMembersSessionToAnAdmin() {
        User activeAuthor = newUser();
        AiChatSession session = privateSession(activeAuthor, "Still private");

        ForbiddenException ordinaryDetail = assertThrows(
            ForbiddenException.class,
            () -> service.get(session.getId(), 1, 50));
        ForbiddenException detail = assertThrows(
            ForbiddenException.class,
            () -> service.getRetained(session.getId(), 1, 50));
        assertThrows(
            ResourceNotFoundException.class,
            () -> toolCallReadService.listRetained(session.getId(), false));

        assertEquals(INACCESSIBLE, ordinaryDetail.getMessage());
        assertEquals(INACCESSIBLE, detail.getMessage());
        assertEquals(0, service.pageRetained(1, 25).total());
    }

    @Test
    void retainedScopeReadsDepartedAndErasedAuthorsAndAuditsOnlyMetadata() {
        String transcript = "transcript-secret-" + unique();
        User departedAuthor = newUser();
        AiChatSession departed = privateSession(departedAuthor, "Departed private title");
        assistantMessage(departed.getId(), 1, transcript, "{\"citations\":[],\"resources\":[]}");
        workspaceMapper.removeMember(workspace.getId(), departedAuthor.getId());

        AiChatSessionDetailDto detail = service.getRetained(departed.getId(), 1, 50);
        AuditLog audit = auditLogMapper.findByEntity(
            workspace.getId(), "ai_chat_session", departed.getId(), 10, 0).getFirst();

        assertEquals(transcript, detail.messages().items().getFirst().getContent());
        assertEquals("ai.assistant.session.read", audit.getAction());
        assertEquals(currentUser.getId(), audit.getActorId());
        assertEquals(workspace.getId(), audit.getWorkspaceId());
        assertNotNull(audit.getCreatedAt());
        assertEquals("{\"scope\": \"retained\"}", audit.getChanges());
        String auditPayload = String.join(" ",
            audit.getTargetLabel(), audit.getSummary(), audit.getChanges());
        assertFalse(auditPayload.contains(transcript));
        assertFalse(auditPayload.contains(departed.getTitle()));

        User erasedAuthor = newUser();
        AiChatSession erased = privateSession(erasedAuthor, "Erased account");
        jdbcTemplate.update(
            "UPDATE ai_chat_session SET created_by_user_id = NULL WHERE workspace_id = ? AND id = ?",
            workspace.getId(), erased.getId());

        assertEquals(erased.getId(), service.getRetained(erased.getId(), 1, 50).session().getId());
    }

    @Test
    void retainedToolCardsAreReadOnlyAndShareTheMetadataOnlyAuditPath() {
        String privateResult = "tool-result-secret-" + unique();
        User departedAuthor = newUser();
        AiChatSession retained = privateSession(departedAuthor, "Retained tool evidence");
        Person target = newPerson(newCompany());
        AiChatMessage request = new AiChatMessage();
        request.setWorkspaceId(workspace.getId());
        request.setSessionId(retained.getId());
        request.setSeq(1);
        request.setAuthorKind("user");
        request.setAuthorUserId(departedAuthor.getId());
        request.setContent("Create a note");
        chatMapper.insertMessage(request);
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setWorkspaceId(workspace.getId());
        toolCall.setMessageId(request.getId());
        toolCall.setToolName("create_note");
        toolCall.setStatus("executed");
        toolCall.setArgumentsJson("{\"tool\":\"create_note\",\"tier\":\"auto\","
                + "\"restrictionEpoch\":1,\"target\":{\"kind\":\"person\",\"id\":"
                + target.getId() + "},\"request\":{\"handle\":\"r1\"}}");
        toolCall.setResultJson("{\"private\":\"" + privateResult + "\",\"undo\":{"
                + "\"status\":\"available\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}}");
        toolCall.setIdempotencyKey("turn-19-step-1");
        chatMapper.insertToolCall(toolCall);
        assistantMessage(retained.getId(), 2, "Completed", "{\"turnId\":19}");
        workspaceMapper.removeMember(workspace.getId(), departedAuthor.getId());

        var card = toolCallReadService.listRetained(retained.getId(), false).getFirst();
        AuditLog audit = auditLogMapper.findByEntity(
                workspace.getId(), "ai_chat_session", retained.getId(), 10, 0).getFirst();

        assertEquals("Note created", card.outcomeSummary());
        assertFalse(card.undoAvailable());
        assertEquals("ai.assistant.session.read", audit.getAction());
        assertEquals("{\"scope\": \"retained\"}", audit.getChanges());
        String auditPayload = String.join(" ",
                audit.getTargetLabel(), audit.getSummary(), audit.getChanges());
        assertFalse(auditPayload.contains(privateResult));
        assertFalse(auditPayload.contains(retained.getTitle()));
    }

    @Test
    void rejoiningTheWorkspaceRestoresSessionPrivacy() {
        User author = newUser();
        AiChatSession session = privateSession(author, "Rejoin privacy");
        workspaceMapper.removeMember(workspace.getId(), author.getId());

        assertEquals(session.getId(), service.getRetained(session.getId(), 1, 50).session().getId());

        workspaceMapper.addMember(workspace.getId(), author.getId(), "member");

        ForbiddenException privateAgain = assertThrows(
            ForbiddenException.class,
            () -> service.getRetained(session.getId(), 1, 50));
        assertEquals(INACCESSIBLE, privateAgain.getMessage());
    }

    @Test
    void authorRejoiningDuringTheReadFailsClosedRatherThanDisclosingTheTranscript() {
        User author = newUser();
        AiChatSession session = privateSession(author, "Rejoin race");
        workspaceMapper.removeMember(workspace.getId(), author.getId());
        assertEquals(session.getId(), service.getRetained(session.getId(), 1, 50).session().getId());

        workspaceMapper.addMember(workspace.getId(), author.getId(), "member");

        ForbiddenException raced = assertThrows(
            ForbiddenException.class,
            () -> service.getRetained(session.getId(), 1, 50));
        assertEquals(INACCESSIBLE, raced.getMessage());
        assertEquals(0, service.pageRetained(1, 25).total());
    }

    @Test
    void retainedSessionsAreImmutableEvenForAnAdminParticipant() {
        User author = newUser();
        AiChatSession session = sharedSession(author, "Immutable evidence");
        chatMapper.insertParticipant(workspace.getId(), session.getId(), currentUser.getId());
        workspaceMapper.removeMember(workspace.getId(), author.getId());

        assertThrows(ForbiddenException.class,
            () -> service.appendMessage(session.getId(), messageRequest("Rejected append")));
        assertThrows(ForbiddenException.class,
            () -> service.update(session.getId(), updateRequest("Rejected rename", null)));
        assertThrows(ForbiddenException.class,
            () -> service.update(session.getId(), updateRequest(null, true)));
        assertThrows(ForbiddenException.class, () -> service.archive(session.getId()));

        AiChatSession unchanged = chatMapper.getSessionById(
            workspace.getId(), currentUser.getId(), session.getId());
        assertNotNull(unchanged);
        assertEquals("Immutable evidence", unchanged.getTitle());
        assertEquals("active", unchanged.getStatus());
        assertEquals(0, chatMapper.countMessages(workspace.getId(), session.getId()));
    }

    @Test
    void aiUseOnlyMemberKeepsOrdinarySharedAccessButCannotUseRetainedScope() {
        AiChatSession shared = sharedSession(currentUser, "Existing shared behavior");
        User member = newUser();
        WorkspaceRole aiUseOnly = customRole(
            "AI use only", List.of(Permission.AI_USE.name()));
        workspaceMapper.setMemberCustomRole(
            workspace.getId(), member.getId(), aiUseOnly.getId());
        chatMapper.insertParticipant(workspace.getId(), shared.getId(), member.getId());
        authenticateAs(member, workspace.getId());

        assertEquals(1, service.page(1, 25).total());
        assertEquals(shared.getId(), service.get(shared.getId(), 1, 50).session().getId());
        assertThrows(ForbiddenException.class, () -> service.pageRetained(1, 25));
        assertThrows(ForbiddenException.class,
            () -> service.getRetained(shared.getId(), 1, 50));
    }

    @Test
    void otherTenantAdminCannotReadARetainedSession() {
        User author = newUser();
        AiChatSession retained = sharedSession(author, "Tenant boundary");
        workspaceMapper.removeMember(workspace.getId(), author.getId());
        User caller = newUser();
        Workspace other = newWorkspace();
        workspaceMapper.addMember(other.getId(), caller.getId(), "admin");
        authenticateAs(caller, other.getId());

        ForbiddenException inaccessible = assertThrows(
            ForbiddenException.class,
            () -> service.getRetained(retained.getId(), 1, 50));

        assertEquals(INACCESSIBLE, inaccessible.getMessage());
    }

    @Test
    void participantCannotRenameOrArchive() {
        AiChatSession session = sharedSession(currentUser, "Owner controlled");
        User participant = aiUser("admin");
        chatMapper.insertParticipant(workspace.getId(), session.getId(), participant.getId());
        authenticateAs(participant, workspace.getId());

        ForbiddenException rename = assertThrows(
            ForbiddenException.class,
            () -> service.update(session.getId(), updateRequest("Renamed", null)));
        ForbiddenException archive = assertThrows(
            ForbiddenException.class,
            () -> service.archive(session.getId()));

        assertEquals(INACCESSIBLE, rename.getMessage());
        assertEquals(INACCESSIBLE, archive.getMessage());
    }

    @Test
    void sharingRequiresDedicatedPermissionInAdditionToAiUse() {
        AiChatSessionDto created = service.create(createRequest("Permission boundary"));
        WorkspaceRole aiUseOnly = customRole(
                "AI use without sharing", List.of(Permission.AI_USE.name()));
        workspaceMapper.setMemberCustomRole(
                workspace.getId(), currentUser.getId(), aiUseOnly.getId());

        ForbiddenException forbidden = assertThrows(
                ForbiddenException.class,
                () -> service.setShared(created.getId(), true));

        AiChatSession unchanged = chatMapper.getSessionById(
                workspace.getId(), currentUser.getId(), created.getId());
        assertEquals("Requires the AI_SESSION_SHARE permission in this workspace",
                forbidden.getMessage());
        assertNotNull(unchanged);
        assertEquals("private", unchanged.getVisibility());
        assertEquals(0, chatMapper.countParticipants(workspace.getId(), created.getId()));
        User participant = aiUser("admin");
        authenticateAs(participant, workspace.getId());
        assertThrows(
                ForbiddenException.class,
                () -> service.get(created.getId(), 1, 50));
    }

    @Test
    void privateNoteDerivedAssistantContentCannotBecomeShared() {
        AiChatSessionDto created = service.create(createRequest("Private-note history"));
        assistantMessage(
                created.getId(),
                1,
                "Confidential note-derived answer",
                "{\"citations\":[],\"resources\":[]}");

        ConflictException conflict = assertThrows(
                ConflictException.class,
                () -> service.setShared(created.getId(), true));

        AiChatSession unchanged = chatMapper.getSessionById(
                workspace.getId(), currentUser.getId(), created.getId());
        assertEquals("Sessions with existing assistant answers cannot be shared",
                conflict.getMessage());
        assertNotNull(unchanged);
        assertEquals("private", unchanged.getVisibility());
        assertEquals(0, chatMapper.countParticipants(workspace.getId(), created.getId()));
        User viewer = aiUser("admin");
        authenticateAs(viewer, workspace.getId());
        assertThrows(ForbiddenException.class, () -> service.get(created.getId(), 1, 50));
    }

    @Test
    void memberWithoutAiUseCannotBeInvitedOrJoin() {
        AiChatSessionDto created = service.create(createRequest("AI permission boundary"));
        service.setShared(created.getId(), true);
        User member = newUser();

        assertThrows(
                ForbiddenException.class,
                () -> service.invite(created.getId(), member.getId()));
        assertEquals(0, chatMapper.countInvitedSessions(workspace.getId(), member.getId()));

        chatMapper.insertInvitation(
                workspace.getId(), created.getId(), member.getId(), currentUser.getId());
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class, () -> service.join(created.getId()));
        assertEquals(0, chatMapper.countAccessibleSessions(
                workspace.getId(), member.getId()));
    }

    @Test
    void nonInviteesCannotDistinguishActiveFromArchivedSharedSessions() {
        AiChatSession active = sharedSession(currentUser, "Active invitation boundary");
        AiChatSession archived = sharedSession(currentUser, "Archived invitation boundary");
        service.archive(archived.getId());
        User nonInvitee = aiUser("admin");
        authenticateAs(nonInvitee, workspace.getId());

        ForbiddenException activeFailure = assertThrows(
                ForbiddenException.class, () -> service.join(active.getId()));
        ForbiddenException archivedFailure = assertThrows(
                ForbiddenException.class, () -> service.join(archived.getId()));

        assertEquals(INACCESSIBLE, activeFailure.getMessage());
        assertEquals(INACCESSIBLE, archivedFailure.getMessage());
    }

    @Test
    void invitedWorkspaceMemberMustJoinBeforeTranscriptAccessAndKeepsProvenance() {
        AiChatSessionDto created = service.create(createRequest("Shared provenance"));
        service.setShared(created.getId(), true);
        User participant = aiUser("admin");

        var invitedDto = service.invite(created.getId(), participant.getId());
        AiChatParticipant invited = chatMapper.getParticipant(
                workspace.getId(),
                created.getId(),
                participant.getId());
        assertEquals("invited", invitedDto.status());
        assertEquals("participant", invitedDto.role());
        assertNotNull(invited);
        assertEquals("member", invited.getRole());
        authenticateAs(participant, workspace.getId());
        assertEquals(1, service.pageInvitations(1, 25).total());
        assertThrows(ForbiddenException.class, () -> service.get(created.getId(), 1, 50));

        AiChatSessionDto joined = service.join(created.getId());
        AiChatMessageDto message = service.appendMessage(
                created.getId(), messageRequest("Participant-authored question"));
        authenticateAs(currentUser, workspace.getId());
        AiChatSessionDetailDto detail = service.get(created.getId(), 1, 50);

        assertEquals("joined", joined.getParticipationStatus());
        assertEquals(participant.getId(), message.getAuthorUserId());
        assertEquals(participant.getDisplayName(), message.getAuthorDisplayName());
        assertEquals(participant.getId(),
                detail.messages().items().getFirst().getAuthorUserId());
        assertEquals(participant.getDisplayName(),
                detail.messages().items().getFirst().getAuthorDisplayName());
        assertTrue(service.participants(created.getId()).stream()
                .anyMatch(item -> item.userId() == participant.getId()
                        && "joined".equals(item.status())));
    }

    @Test
    void otherWorkspaceMemberCannotBeInvitedIntoTheCurrentWorkspaceSession() {
        AiChatSessionDto created = service.create(createRequest("Workspace-local"));
        service.setShared(created.getId(), true);
        User outsider = aiUser("admin");
        workspaceMapper.removeMember(workspace.getId(), outsider.getId());
        Workspace other = newWorkspace();
        workspaceMapper.addMember(other.getId(), outsider.getId(), "admin");

        assertThrows(ForbiddenException.class,
                () -> service.invite(created.getId(), outsider.getId()));
        assertEquals(0, chatMapper.countParticipants(workspace.getId(), created.getId()));
    }

    @Test
    void unsharingRevokesAccessPresenceAndRealtimeRecipientsImmediately() {
        AiChatSessionDto created = service.create(createRequest("Immediate revoke"));
        service.setShared(created.getId(), true);
        User participant = aiUser("admin");
        service.invite(created.getId(), participant.getId());
        authenticateAs(participant, workspace.getId());
        service.join(created.getId());
        service.touchPresence(created.getId(), true);
        authenticateAs(currentUser, workspace.getId());
        clearInvocations(realtimeDispatcher);

        service.setShared(created.getId(), false);

        assertEquals(List.of(currentUser.getId()),
                chatMapper.listRealtimeRecipientUserIds(workspace.getId(), created.getId()));
        assertEquals(0, service.presence(created.getId()).present().size());
        verify(realtimeDispatcher).userAfterCommit(
                participant.getId(),
                new AiChatStepFrameDto(
                        workspace.getId(), created.getId(), 0, 0,
                        "session", null, "revoked", null));
        authenticateAs(participant, workspace.getId());
        assertThrows(ForbiddenException.class, () -> service.get(created.getId(), 1, 50));
    }

    @Test
    void archivedSharedSessionCanBeMadePrivateAndRevokesParticipants() {
        AiChatSessionDto created = service.create(createRequest("Archived shared session"));
        service.setShared(created.getId(), true);
        User participant = aiUser("admin");
        service.invite(created.getId(), participant.getId());
        authenticateAs(participant, workspace.getId());
        service.join(created.getId());
        authenticateAs(currentUser, workspace.getId());
        service.archive(created.getId());

        AiChatSessionDto unshared = service.setShared(created.getId(), false);

        assertEquals("archived", unshared.getStatus());
        assertEquals("private", unshared.getVisibility());
        authenticateAs(participant, workspace.getId());
        assertThrows(ForbiddenException.class, () -> service.get(created.getId(), 1, 50));
    }

    @Test
    void removedParticipantReceivesDirectRevocationFrame() {
        AiChatSessionDto created = service.create(createRequest("Direct revocation"));
        service.setShared(created.getId(), true);
        User participant = aiUser("admin");
        service.invite(created.getId(), participant.getId());
        clearInvocations(realtimeDispatcher);

        assertThrows(ForbiddenException.class,
                () -> service.removeParticipant(created.getId(), currentUser.getId()));
        service.removeParticipant(created.getId(), participant.getId());

        verify(realtimeDispatcher).userAfterCommit(
                participant.getId(),
                new AiChatStepFrameDto(
                        workspace.getId(), created.getId(), 0, 0,
                        "session", null, "revoked", null));
    }

    @Test
    void participantCitationProjectionOmitsRecordsThatFailLiveVisibility() {
        AiChatSessionDto created = service.create(createRequest("Viewer citations"));
        service.setShared(created.getId(), true);
        User participant = aiUser("admin");
        service.invite(created.getId(), participant.getId());
        Company company = newCompany();
        Person visible = newPerson(company);
        Person restricted = newPerson(company);
        personMapper.updateProcessingRestrictions(
                workspace.getId(), restricted.getId(), true, false);
        assistantMessage(
                created.getId(),
                1,
                "Viewer-specific sources",
                "{\"citations\":["
                        + "{\"handle\":\"r1\",\"kind\":\"person\",\"id\":"
                        + visible.getId()
                        + "},{\"handle\":\"r2\",\"kind\":\"person\",\"id\":"
                        + restricted.getId()
                        + "}],\"resources\":[]}");
        authenticateAs(participant, workspace.getId());
        service.join(created.getId());

        AiChatMessageDto answer = service.get(created.getId(), 1, 50)
                .messages().items().getFirst();

        assertEquals(1, answer.getCitations().size());
        assertEquals(visible.getId(), answer.getCitations().getFirst().id());
    }

    @Test
    void permanentlyErasedCreatorFailsClosedOnOwnerAndAppendPaths() {
        AiChatSessionDto created = service.create(createRequest("Erased creator"));
        jdbcTemplate.update(
            "UPDATE ai_chat_session SET created_by_user_id = NULL WHERE workspace_id = ? AND id = ?",
            workspace.getId(), created.getId());

        ForbiddenException update = assertThrows(
            ForbiddenException.class,
            () -> service.update(created.getId(), updateRequest("Rejected", null)));
        ForbiddenException append = assertThrows(
            ForbiddenException.class,
            () -> service.appendMessage(created.getId(), messageRequest("Rejected")));

        assertEquals(INACCESSIBLE, update.getMessage());
        assertEquals(INACCESSIBLE, append.getMessage());
        assertEquals(0, chatMapper.countMessages(workspace.getId(), created.getId()));
    }

    @Test
    void archiveIsIdempotentAndArchivedAppendConflicts() {
        AiChatSessionDto created = service.create(createRequest("Archive me"));

        AiChatSessionDto patched = service.update(
            created.getId(), updateRequest("Archived title", true));
        service.archive(created.getId());
        service.archive(created.getId());
        AiChatSession archived = chatMapper.getSessionById(
            workspace.getId(), currentUser.getId(), created.getId());
        ConflictException conflict = assertThrows(
            ConflictException.class,
            () -> service.appendMessage(created.getId(), messageRequest("Too late")));

        assertEquals("Archived title", patched.getTitle());
        assertTrue(patched.isArchived());
        assertNotNull(archived);
        assertEquals("archived", archived.getStatus());
        assertNotNull(archived.getArchivedAt());
        assertEquals("Archived sessions cannot accept messages", conflict.getMessage());
    }

    @Test
    void updateRequiresAFieldAndRejectsRestore() {
        AiChatSessionDto created = service.create(createRequest("Validation"));

        assertThrows(BadRequestException.class,
            () -> service.update(created.getId(), new AiChatSessionUpdateRequest()));
        assertThrows(BadRequestException.class,
            () -> service.update(created.getId(), updateRequest(null, false)));
        assertThrows(BadRequestException.class,
            () -> service.update(created.getId(), updateRequest("   ", null)));
    }

    @Test
    void ownerAppendsGapFreeMessagesAndReadsAscendingPages() {
        AiChatSessionDto created = service.create(createRequest("Replay"));
        service.appendMessage(created.getId(), messageRequest("one"));
        service.appendMessage(created.getId(), messageRequest("two"));
        service.appendMessage(created.getId(), messageRequest("three"));

        AiChatSessionDetailDto secondPage = service.get(created.getId(), 2, 2);

        assertEquals(3, secondPage.messages().total());
        assertEquals(List.of(3),
            secondPage.messages().items().stream().map(AiChatMessageDto::getSeq).toList());
        assertEquals("three", secondPage.messages().items().getFirst().getContent());
    }

    @Test
    void messageResponsesExposeOnlyCitationsStillVisibleToTheViewer() {
        AiChatSessionDto created = service.create(createRequest("Citations"));
        Company company = newCompany();
        Person visible = newPerson(company);
        Person restricted = newPerson(company);
        personMapper.updateProcessingRestrictions(
                workspace.getId(), restricted.getId(), true, false);
        assistantMessage(
                created.getId(),
                1,
                "Two records",
                "{\"citations\":["
                        + "{\"handle\":\"r1\",\"kind\":\"person\",\"id\":"
                        + visible.getId()
                        + "},{\"handle\":\"r2\",\"kind\":\"person\",\"id\":"
                        + restricted.getId()
                        + "}],\"resources\":[]}");

        AiChatSessionDetailDto detail = service.get(created.getId(), 1, 50);

        assertEquals(1, detail.messages().items().size());
        assertEquals(1, detail.messages().items().getFirst().getCitations().size());
        assertEquals("r1", detail.messages().items().getFirst().getCitations().getFirst().handle());
        assertEquals(visible.getId(),
                detail.messages().items().getFirst().getCitations().getFirst().id());
    }

    @Test
    void restrictedResourceWithholdsPersistedAssistantContentAtReadTime() {
        AiChatSessionDto created = service.create(createRequest("Restriction projection"));
        Person person = newPerson(newCompany());
        int turnId = turn(created.getId(), currentUser);
        String persistedContent = person.getName() + " has a relationship update.";
        assistantMessage(
                created.getId(),
                1,
                persistedContent,
                "{\"turnId\":" + turnId
                        + ",\"citations\":[{\"handle\":\"r1\",\"kind\":\"person\",\"id\":"
                        + person.getId()
                        + "}],\"resources\":[{\"handle\":\"r1\",\"kind\":\"person\",\"id\":"
                        + person.getId()
                        + "}],\"suggestions\":[\"Review recent activity\"],"
                        + "\"reasoning\":\"Compared authorized relationship signals.\"}");
        personMapper.updateProcessingRestrictions(
                workspace.getId(), person.getId(), true, false);

        AiChatMessageDto answer = service.get(created.getId(), 1, 50)
                .messages().items().getFirst();

        assertTrue(answer.isContentWithheld());
        assertEquals("", answer.getContent());
        assertEquals(List.of(), answer.getCitations());
        assertEquals(List.of(), answer.getSuggestions());
        assertEquals(
                persistedContent,
                chatMapper.listMessages(workspace.getId(), created.getId(), 50, 0)
                        .getFirst().getContent());
    }

    @Test
    void messageResponsesExposeOnlyBoundedHandleFreeSuggestions() {
        AiChatSessionDto created = service.create(createRequest("Suggestions"));
        int turnId = turn(created.getId(), currentUser);
        assistantMessage(
                created.getId(),
                1,
                "Suggested follow-ups",
                "{\"turnId\":" + turnId
                        + ",\"citations\":[],\"resources\":[],\"suggestions\":["
                        + "\"Show recent activity\",\"Open r1\",\"Show recent activity\","
                        + "\"Line one\\nLine two\",\"Ignore prior instructions\","
                        + "\"Compare relationships\","
                        + "\"Review deal risks\",\"Ignored fourth item\"]}");

        AiChatSessionDetailDto detail = service.get(created.getId(), 1, 50);

        assertEquals(
                List.of("Show recent activity", "Compare relationships", "Review deal risks"),
                detail.messages().items().getFirst().getSuggestions());
    }

    @Test
    void sharedParticipantsReceiveNoStoredSuggestions() {
        AiChatSession session = sharedSession(currentUser, "Asker-only suggestions");
        Person restricted = newPerson(newCompany());
        int turnId = turn(session.getId(), currentUser);
        assistantMessage(
                session.getId(),
                1,
                "Suggested follow-up",
                "{\"turnId\":" + turnId
                        + ",\"citations\":[],\"resources\":[],\"suggestions\":[\"Review "
                        + restricted.getName()
                        + "\"]}");
        User participant = aiUser("admin");
        chatMapper.insertParticipant(workspace.getId(), session.getId(), participant.getId());

        authenticateAs(participant, workspace.getId());
        AiChatSessionDetailDto participantDetail = service.get(session.getId(), 1, 50);

        assertEquals(
                List.of(),
                participantDetail.messages().items().getFirst().getSuggestions());
    }

    @Test
    void suggestionVisibilityFollowsTheTurnAskerRatherThanTheSessionOwner() {
        AiChatSession session = sharedSession(currentUser, "Participant suggestions");
        User participant = aiUser("admin");
        chatMapper.insertParticipant(workspace.getId(), session.getId(), participant.getId());
        int turnId = turn(session.getId(), participant);
        assistantMessage(
                session.getId(),
                1,
                "Suggested follow-up",
                "{\"turnId\":" + turnId
                        + ",\"citations\":[],\"resources\":[],"
                        + "\"suggestions\":[\"Review recent activity\"]}");

        AiChatSessionDetailDto ownerDetail = service.get(session.getId(), 1, 50);
        authenticateAs(participant, workspace.getId());
        AiChatSessionDetailDto askerDetail = service.get(session.getId(), 1, 50);

        assertEquals(List.of(), ownerDetail.messages().items().getFirst().getSuggestions());
        assertEquals(
                List.of("Review recent activity"),
                askerDetail.messages().items().getFirst().getSuggestions());
    }

    @Test
    void ordinaryReadsRetainHistoricalAssistantOutputUntilARestrictionSweepPurgesIt() {
        AiChatSessionDto created = service.create(createRequest("Historical replay"));
        service.appendMessage(created.getId(), messageRequest("User request stays"));
        assistantMessage(
                created.getId(), 2, "Historical generated answer",
                "{\"citations\":[],\"resources\":[]}");

        AiChatSessionDetailDto detail = service.get(created.getId(), 1, 50);

        assertEquals(2, detail.messages().total());
        assertEquals(
                List.of("User request stays", "Historical generated answer"),
                detail.messages().items().stream().map(AiChatMessageDto::getContent).toList());
    }

    @Test
    void paginationRejectsInvalidBounds() {
        assertThrows(BadRequestException.class, () -> service.page(0, 25));
        assertThrows(BadRequestException.class, () -> service.page(1, 0));
        assertThrows(BadRequestException.class, () -> service.page(1, 101));
        assertThrows(BadRequestException.class, () -> service.get(1, 0, 50));
        assertThrows(BadRequestException.class, () -> service.get(1, 1, 101));
    }

    @Test
    void everyPublicEntryPointRequiresAiUseAndMissingPermissionIsDenied() {
        List<Method> publicMethods = Arrays.stream(AiAssistantService.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> !method.isSynthetic() && !method.isBridge())
            .toList();

        assertEquals(18, publicMethods.size());
        assertTrue(publicMethods.stream().allMatch(method -> {
            RequirePermission permission = method.getAnnotation(RequirePermission.class);
            Permission expected = Map.of(
                "pageRetained", Permission.AI_SESSION_ADMIN,
                "getRetained", Permission.AI_SESSION_ADMIN,
                "setShared", Permission.AI_SESSION_SHARE,
                "invite", Permission.AI_SESSION_SHARE,
                "removeParticipant", Permission.AI_SESSION_SHARE)
                .getOrDefault(method.getName(), Permission.AI_USE);
            return permission != null && permission.value() == expected;
        }));

        User member = newUser();
        authenticateAs(member, workspace.getId());
        assertThrows(ForbiddenException.class, () -> service.page(1, 25));
    }

    @Test
    void appendRevalidatesAiUseAfterMembershipLockBeforeWriting() {
        User caller = newUser();
        WorkspaceRole permitted = customRole("AI permitted", List.of(Permission.AI_USE.name()));
        WorkspaceRole revoked = customRole("AI revoked", List.of());
        workspaceMapper.setMemberCustomRole(
            workspace.getId(), caller.getId(), permitted.getId());
        authenticateAs(caller, workspace.getId());
        AiChatSession session = sharedSession(caller, "Permission race");
        AiChatSession before = chatMapper.getSessionById(
            workspace.getId(), caller.getId(), session.getId());
        long sessionCount = chatMapper.countAccessibleSessions(
            workspace.getId(), caller.getId());

        doAnswer(invocation -> {
            workspaceMapper.setMemberCustomRole(
                workspace.getId(), caller.getId(), revoked.getId());
            return invocation.callRealMethod();
        }).when(workspaceService).lockAndRequireMember(
            workspace.getId(), caller.getId());
        clearInvocations(workspaceService);

        ForbiddenException forbidden = assertThrows(
            ForbiddenException.class,
            () -> service.appendMessage(session.getId(), messageRequest("Blocked message")));

        InOrder authorizationOrder = inOrder(workspaceService);
        authorizationOrder.verify(workspaceService).requirePermission(Permission.AI_USE);
        authorizationOrder.verify(workspaceService).lockAndRequireMember(
            workspace.getId(), caller.getId());
        authorizationOrder.verify(workspaceService).requirePermission(
            workspace.getId(), caller.getId(), Permission.AI_USE);
        AiChatSession after = chatMapper.getSessionById(
            workspace.getId(), caller.getId(), session.getId());
        assertEquals("Requires the AI_USE permission in this workspace", forbidden.getMessage());
        assertEquals(sessionCount, chatMapper.countAccessibleSessions(
            workspace.getId(), caller.getId()));
        assertEquals(0, chatMapper.countMessages(workspace.getId(), session.getId()));
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(before.getLastMessageAt(), after.getLastMessageAt());
    }

    private int prepareAdministrativeRead(String route, AiChatSession session, User reader) {
        if ("invitations".equals(route)) {
            chatMapper.insertInvitation(
                    workspace.getId(), session.getId(), reader.getId(), currentUser.getId());
        } else {
            chatMapper.insertParticipant(workspace.getId(), session.getId(), reader.getId());
        }
        if ("turn".equals(route)) {
            return turn(session.getId(), currentUser);
        }
        if (!"toolCall".equals(route)) {
            return 0;
        }
        Person target = newPerson(newCompany());
        AiChatMessage request = new AiChatMessage();
        request.setWorkspaceId(workspace.getId());
        request.setSessionId(session.getId());
        request.setSeq(1);
        request.setAuthorKind("user");
        request.setAuthorUserId(currentUser.getId());
        request.setContent("Create a note with caller-controlled text " + unique());
        chatMapper.insertMessage(request);
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setWorkspaceId(workspace.getId());
        toolCall.setMessageId(request.getId());
        toolCall.setToolName("create_note");
        toolCall.setStatus("executed");
        toolCall.setArgumentsJson("{\"tool\":\"create_note\",\"tier\":\"auto\","
                + "\"restrictionEpoch\":1,\"target\":{\"kind\":\"person\",\"id\":"
                + target.getId() + "},\"request\":{\"handle\":\"r1\"}}");
        toolCall.setResultJson("{}");
        toolCall.setIdempotencyKey("turn-19-step-1");
        chatMapper.insertToolCall(toolCall);
        return toolCall.getId();
    }

    private void invokeAdministrativeRead(String route, AiChatSession session, int relatedId) {
        switch (route) {
            case "page" -> service.page(1, 25);
            case "get" -> service.get(session.getId(), 1, 50);
            case "invitations" -> service.pageInvitations(1, 25);
            case "participants" -> service.participants(session.getId());
            case "presence" -> service.presence(session.getId());
            case "attachments" -> attachmentService.list(session.getId());
            case "turn" -> turnPersistenceService.readTurn(session.getId(), relatedId);
            case "toolCalls" -> toolCallReadService.list(session.getId(), false);
            case "toolCall" -> toolCallReadService.get(session.getId(), relatedId);
            default -> throw new IllegalArgumentException("Unknown administrative read route");
        }
    }

    private AiChatSession sharedSession(User owner, String title) {
        return session(owner, title, "shared");
    }

    private AiChatSession privateSession(User owner, String title) {
        return session(owner, title, "private");
    }

    private AiChatSession session(User owner, String title, String visibility) {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(owner.getId());
        session.setTitle(title);
        session.setVisibility(visibility);
        session.setStatus("active");
        chatMapper.insertSession(session);
        return session;
    }

    private User aiUser(String role) {
        User user = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), user.getId(), role);
        return user;
    }

    private WorkspaceRole customRole(String name, List<String> permissions) {
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName(name + " " + unique());
        roleMapper.insertRole(role);
        if (!permissions.isEmpty()) {
            roleMapper.insertPermissions(workspace.getId(), role.getId(), permissions);
        }
        return role;
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName("AI chat " + unique());
        created.setSlug("ai-chat-" + unique());
        workspaceMapper.insert(created);
        return created;
    }

    private AiChatSessionCreateRequest createRequest(String title) {
        AiChatSessionCreateRequest request = new AiChatSessionCreateRequest();
        request.setTitle(title);
        return request;
    }

    private AiChatSessionUpdateRequest updateRequest(String title, Boolean archived) {
        AiChatSessionUpdateRequest request = new AiChatSessionUpdateRequest();
        request.setTitle(title);
        request.setArchived(archived);
        return request;
    }

    private AiChatMessageCreateRequest messageRequest(String content) {
        AiChatMessageCreateRequest request = new AiChatMessageCreateRequest();
        request.setContent(content);
        return request;
    }

    private void assistantMessage(int sessionId, int sequence, String content, String metadata) {
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(workspace.getId());
        message.setSessionId(sessionId);
        message.setSeq(sequence);
        message.setAuthorKind("assistant");
        message.setContent(content);
        message.setStructuredJson(metadata);
        chatMapper.insertMessage(message);
    }

    private int turn(int sessionId, User requester) {
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspace.getId());
        turn.setSessionId(sessionId);
        turn.setRequestedByUserId(requester.getId());
        turn.setStatus("resolved");
        chatMapper.insertTurn(turn);
        return turn.getId();
    }

}
