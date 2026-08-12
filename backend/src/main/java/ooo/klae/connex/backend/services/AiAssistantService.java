package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiChatCitationProjector;
import ooo.klae.connex.backend.ai.assistant.AiChatPresenceRegistry;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatParticipant;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
import ooo.klae.connex.backend.dto.AiChatParticipantDto;
import ooo.klae.connex.backend.dto.AiChatPresenceDto;
import ooo.klae.connex.backend.dto.AiChatSessionCreateRequest;
import ooo.klae.connex.backend.dto.AiChatSessionDetailDto;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.dto.AiChatSessionUpdateRequest;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Durable assistant chat CRUD, access policy, and per-session message sequencing.
 * Mutations revalidate {@link Permission#AI_USE} against the caller's locked membership state.
 */
@Service
@RequiredArgsConstructor
public class AiAssistantService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE_OFFSET = 100_000;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_CONTENT_LENGTH = 16_000;
    private static final String PRIVATE = "private";
    private static final String SHARED = "shared";
    private static final String ACTIVE = "active";
    private static final String ARCHIVED = "archived";
    private static final String USER = "user";
    private static final String INACCESSIBLE = "AI assistant session is not accessible";

    private final AiChatMapper chatMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AiChatCitationProjector citationProjector;
    private final AuditService auditService;
    private final UserMapper userMapper;
    private final AiChatPresenceRegistry presenceRegistry;
    private final AiChatRealtimeDispatcher realtimeDispatcher;

    /** Returns an ordered page of caller-owned and explicitly shared sessions. */
    @Transactional
    @RequirePermission(Permission.AI_USE)
    public PageResponse<AiChatSessionDto> page(int page, int size) {
        int offset = pageOffset(page, size);
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        List<AiChatSession> sessions = chatMapper.listAccessibleSessions(
            workspaceId, userId, size, offset);
        auditAdministrativeReads(workspaceId, userId, sessions);
        List<AiChatSessionDto> items = sessions.stream().map(AiChatSessionDto::from).toList();
        return new PageResponse<>(items, chatMapper.countAccessibleSessions(workspaceId, userId));
    }

    /** Returns pending invitations addressed to the authenticated workspace member. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public PageResponse<AiChatSessionDto> pageInvitations(int page, int size) {
        int offset = pageOffset(page, size);
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        List<AiChatSession> sessions = chatMapper.listInvitedSessions(
                workspaceId, userId, size, offset);
        return new PageResponse<>(
                sessions.stream().map(AiChatSessionDto::from).toList(),
                chatMapper.countInvitedSessions(workspaceId, userId));
    }

    /** Returns an ordered page of sessions whose creators are no longer active members. */
    @Transactional
    @RequirePermission(Permission.AI_SESSION_ADMIN)
    public PageResponse<AiChatSessionDto> pageRetained(int page, int size) {
        int offset = pageOffset(page, size);
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        List<Integer> activeMemberIds = activeMemberIds(workspaceId, userId);
        List<AiChatSession> candidates = chatMapper.listRetainedSessions(
            workspaceId, userId, activeMemberIds, size, offset);
        List<Integer> revalidatedMemberIds = activeMemberIds(workspaceId, userId);
        List<AiChatSession> sessions = candidates.stream()
            .filter(session -> session.getCreatedByUserId() == null
                || !revalidatedMemberIds.contains(session.getCreatedByUserId()))
            .toList();
        sessions.forEach(this::auditRetainedRead);
        return new PageResponse<>(
            sessions.stream().map(AiChatSessionDto::from).toList(),
            chatMapper.countRetainedSessions(workspaceId, revalidatedMemberIds));
    }

    /** Creates a private active session owned by the authenticated caller. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatSessionDto create(AiChatSessionCreateRequest request) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspaceId);
        session.setCreatedByUserId(userId);
        session.setTitle(requireTitle(request == null ? null : request.getTitle()));
        session.setVisibility(PRIVATE);
        session.setStatus(ACTIVE);
        chatMapper.insertSession(session);
        return AiChatSessionDto.from(requireSession(workspaceId, userId, session.getId()));
    }

    /** Returns one accessible session together with an ordered page of messages. */
    @Transactional
    @RequirePermission(Permission.AI_USE)
    public AiChatSessionDetailDto get(int id, int messagePage, int messageSize) {
        int offset = pageOffset(messagePage, messageSize);
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        AiChatSession session = requireAccessible(workspaceId, userId, id);
        auditAdministrativeReads(workspaceId, userId, List.of(session));
        return detail(workspaceId, id, messageSize, offset, session);
    }

    /** Returns one retained session and records the metadata-only administrative read. */
    @Transactional
    @RequirePermission(Permission.AI_SESSION_ADMIN)
    public AiChatSessionDetailDto getRetained(int id, int messagePage, int messageSize) {
        int offset = pageOffset(messagePage, messageSize);
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        List<Integer> activeMemberIds = activeMemberIds(workspaceId, userId);
        AiChatSession session = chatMapper.getRetainedSessionById(
            workspaceId, userId, id, activeMemberIds);
        if (session == null) {
            throw inaccessible();
        }
        requireStillRetained(workspaceId, userId, session);
        auditRetainedRead(session);
        return detail(workspaceId, id, messageSize, offset, session);
    }

    /**
     * Re-derives retention from a fresh membership read before any transcript is disclosed. The
     * first snapshot is taken from the control plane and the session from the tenant plane, so an
     * author who rejoins between the two reads would otherwise still classify as departed and their
     * now-private transcript would be returned. Revalidating fails closed on that race.
     */
    private void requireStillRetained(int workspaceId, int userId, AiChatSession session) {
        if (session.getCreatedByUserId() == null) {
            return;
        }
        if (activeMemberIds(workspaceId, userId).contains(session.getCreatedByUserId())) {
            throw inaccessible();
        }
    }

    private AiChatSessionDetailDto detail(
            int workspaceId,
            int id,
            int messageSize,
            int offset,
            AiChatSession session) {
        List<AiChatMessage> storedMessages = chatMapper.listMessages(
            workspaceId, id, messageSize, offset);
        var citations = citationProjector.project(workspaceId, storedMessages);
        Map<Integer, String> authorNames = authorNames(storedMessages);
        List<AiChatMessageDto> messages = storedMessages.stream()
            .map(message -> AiChatMessageDto.from(
                    message,
                    citations.getOrDefault(message.getId(), List.of()),
                    message.getAuthorUserId() == null
                            ? null
                            : authorNames.get(message.getAuthorUserId())))
            .toList();
        return new AiChatSessionDetailDto(
            AiChatSessionDto.from(session),
            new PageResponse<>(messages, chatMapper.countMessages(workspaceId, id)));
    }

    /** Applies an owner-only title change and/or one-way archive transition. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatSessionDto update(int id, AiChatSessionUpdateRequest request) {
        if (request == null || (request.getTitle() == null && request.getArchived() == null)) {
            throw new BadRequestException("At least one session field is required");
        }
        if (Boolean.FALSE.equals(request.getArchived())) {
            throw new BadRequestException("Archived sessions cannot be restored");
        }
        String title = request.getTitle() == null ? null : requireTitle(request.getTitle());
        String status = Boolean.TRUE.equals(request.getArchived()) ? ARCHIVED : null;
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        requireOwnedLocked(workspaceId, userId, id);
        if (chatMapper.updateSession(workspaceId, id, title, status, null) != 1) {
            throw inaccessible();
        }
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, "updated"));
        return AiChatSessionDto.from(requireSession(workspaceId, userId, id));
    }

    /** Idempotently soft-archives an owned session. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public void archive(int id) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        AiChatSession session = requireOwnedLocked(workspaceId, userId, id);
        if (!ARCHIVED.equals(session.getStatus())
                && chatMapper.updateSession(workspaceId, id, null, ARCHIVED, null) != 1) {
            throw inaccessible();
        }
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, "archived"));
    }

    /** Makes an owned session shared or private after locked share-permission revalidation. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_SESSION_SHARE)
    public AiChatSessionDto setShared(int id, boolean shared) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequirePermissions(
                workspaceId,
                Map.of(userId, Set.of(Permission.AI_USE, Permission.AI_SESSION_SHARE)));
        AiChatSession session = requireOwnedLocked(workspaceId, userId, id);
        if (shared && !ACTIVE.equals(session.getStatus())) {
            throw new ConflictException("Archived sessions cannot be shared");
        }
        if (shared && !SHARED.equals(session.getVisibility())
                && chatMapper.countActiveTurns(workspaceId, id) != 0) {
            throw new ConflictException("Sessions with active turns cannot be shared");
        }
        if (shared && !SHARED.equals(session.getVisibility())
                && chatMapper.countAssistantMessages(workspaceId, id) != 0) {
            throw new ConflictException(
                    "Sessions with existing assistant answers cannot be shared");
        }
        String visibility = shared ? SHARED : PRIVATE;
        if (!visibility.equals(session.getVisibility())
                && chatMapper.updateSession(workspaceId, id, null, null, visibility) != 1) {
            throw inaccessible();
        }
        List<Integer> revokedUserIds = List.of();
        if (!shared) {
            revokedUserIds = chatMapper.listParticipants(workspaceId, id).stream()
                    .map(AiChatParticipant::getUserId)
                    .distinct()
                    .sorted()
                    .toList();
            chatMapper.deleteParticipantsForSession(workspaceId, id);
            presenceRegistry.clear(workspaceId, id);
        }
        auditService.recordStrict(
                shared ? "ai.assistant.session.share" : "ai.assistant.session.unshare",
                "ai_chat_session",
                id,
                "Assistant session " + id,
                shared ? "Shared assistant session" : "Made assistant session private",
                null);
        revokedUserIds.forEach(targetUserId -> realtimeDispatcher.userAfterCommit(
                targetUserId, sessionFrame(workspaceId, id, "revoked")));
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, shared ? SHARED : PRIVATE));
        return AiChatSessionDto.from(requireSession(workspaceId, userId, id));
    }

    /** Invites one active member of the current workspace into an owned shared session. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_SESSION_SHARE)
    public AiChatParticipantDto invite(int id, int targetUserId) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        if (targetUserId == userId) {
            throw new BadRequestException("The session owner is already a participant");
        }
        workspaceService.lockAndRequirePermissions(
                workspaceId,
                Map.of(
                        userId, Set.of(Permission.AI_USE, Permission.AI_SESSION_SHARE),
                        targetUserId, Set.of(Permission.AI_USE)));
        AiChatSession session = requireOwnedLocked(workspaceId, userId, id);
        requireSharedActive(session);
        AiChatParticipant existing = chatMapper.getParticipant(
                workspaceId, id, targetUserId);
        if (existing == null) {
            chatMapper.insertInvitation(workspaceId, id, targetUserId, userId);
        }
        AiChatParticipant invited = chatMapper.getParticipant(workspaceId, id, targetUserId);
        if (invited == null) {
            throw new IllegalStateException("Assistant session invitation is unavailable");
        }
        Map<Integer, User> members = activeMembers(workspaceId);
        User target = members.get(targetUserId);
        if (target == null) {
            throw inaccessible();
        }
        auditService.recordStrict(
                "ai.assistant.session.invite",
                "ai_chat_session",
                id,
                "Assistant session " + id,
                "Invited assistant session participant",
                Map.of("participantUserId", targetUserId));
        realtimeDispatcher.userAfterCommit(
                targetUserId, sessionFrame(workspaceId, id, "invited"));
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, "participants_changed"));
        return participantDto(invited, target, targetUserId == userId);
    }

    /** Accepts the authenticated member's pending invitation without widening any permissions. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatSessionDto join(int id) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequirePermissions(
                workspaceId, Map.of(userId, Set.of(Permission.AI_USE)));
        AiChatSession session = requireLocked(workspaceId, userId, id);
        requireSharedActive(session);
        AiChatParticipant invitation = chatMapper.getParticipant(workspaceId, id, userId);
        if (invitation == null || !"invited".equals(invitation.getStatus())
                || chatMapper.joinParticipant(workspaceId, id, userId) != 1) {
            throw inaccessible();
        }
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, "participant_joined"));
        return AiChatSessionDto.from(requireSession(workspaceId, userId, id));
    }

    /** Leaves or declines a non-owner session and removes access immediately. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public void leave(int id) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        AiChatSession session = requireLocked(workspaceId, userId, id);
        if (Objects.equals(session.getCreatedByUserId(), userId)
                || chatMapper.deleteParticipant(workspaceId, id, userId) != 1) {
            throw inaccessible();
        }
        presenceRegistry.remove(workspaceId, id, userId);
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, "participant_left"));
    }

    /** Removes one invited or joined participant from an owned shared session. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_SESSION_SHARE)
    public void removeParticipant(int id, int targetUserId) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        if (targetUserId == userId) {
            throw inaccessible();
        }
        workspaceService.lockAndRequirePermissions(
                workspaceId,
                Map.of(
                        userId, Set.of(Permission.AI_USE, Permission.AI_SESSION_SHARE),
                        targetUserId, Set.of()));
        requireOwnedLocked(workspaceId, userId, id);
        if (chatMapper.deleteParticipant(workspaceId, id, targetUserId) != 1) {
            throw inaccessible();
        }
        presenceRegistry.remove(workspaceId, id, targetUserId);
        realtimeDispatcher.userAfterCommit(
                targetUserId, sessionFrame(workspaceId, id, "revoked"));
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, "participants_changed"));
    }

    /** Lists the owner and participant states visible to the current session member. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public List<AiChatParticipantDto> participants(int id) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        AiChatSession session = requireAccessible(workspaceId, userId, id);
        return participantDtos(workspaceId, userId, session);
    }

    /** Returns live presence after current session authorization. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public AiChatPresenceDto presence(int id) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        AiChatSession session = requireAccessible(workspaceId, userId, id);
        return presenceDto(
                id,
                participantDtos(workspaceId, userId, session),
                presenceRegistry.snapshot(workspaceId, id));
    }

    /** Records one authorized presence heartbeat and broadcasts a metadata-only invalidation. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public AiChatPresenceDto touchPresence(int id, boolean typing) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        AiChatSession session = requireAccessible(workspaceId, userId, id);
        AiChatPresenceRegistry.Snapshot snapshot = presenceRegistry.touch(
                workspaceId, id, userId, typing);
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, "presence_changed"));
        return presenceDto(
                id, participantDtos(workspaceId, userId, session), snapshot);
    }

    /** Removes the authenticated participant's presence without leaving the session. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public void leavePresence(int id) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        requireAccessible(workspaceId, userId, id);
        presenceRegistry.remove(workspaceId, id, userId);
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, "presence_changed"));
    }

    /** Appends a user message after serializing sequence allocation on the session root. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatMessageDto appendMessage(int id, AiChatMessageCreateRequest request) {
        String content = requireContent(request == null ? null : request.getContent());
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        List<Integer> activeMemberIds = activeMemberIds(workspaceId, userId);
        AiChatSession session = requireLocked(workspaceId, userId, id);
        requireActiveAuthor(session, activeMemberIds);
        requireAppendAccess(workspaceId, userId, session);
        if (ARCHIVED.equals(session.getStatus())) {
            throw new ConflictException("Archived sessions cannot accept messages");
        }
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(workspaceId);
        message.setSessionId(id);
        message.setSeq(chatMapper.nextMessageSequence(workspaceId, id));
        message.setAuthorKind(USER);
        message.setAuthorUserId(userId);
        message.setContent(content);
        chatMapper.insertMessage(message);
        chatMapper.updateLastMessageAt(workspaceId, id);
        AiChatMessage stored = chatMapper.getMessageById(workspaceId, id, message.getId());
        if (stored == null) {
            throw new IllegalStateException("Inserted assistant chat message is unavailable");
        }
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, id, sessionFrame(workspaceId, id, "message_created"));
        return AiChatMessageDto.from(stored, List.of(), authService.getCurrentUser().getDisplayName());
    }

    private Map<Integer, String> authorNames(List<AiChatMessage> messages) {
        List<Integer> authorIds = messages.stream()
                .map(AiChatMessage::getAuthorUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.getDisplayNamesByIds(authorIds).stream()
                .collect(Collectors.toUnmodifiableMap(
                        UserDisplayNameDto::id, UserDisplayNameDto::displayName));
    }

    private List<AiChatParticipantDto> participantDtos(
            int workspaceId, int userId, AiChatSession session) {
        Map<Integer, User> members = activeMembers(workspaceId);
        List<AiChatParticipantDto> participants = new java.util.ArrayList<>();
        Integer ownerId = session.getCreatedByUserId();
        if (ownerId != null && members.containsKey(ownerId)) {
            User owner = members.get(ownerId);
            participants.add(new AiChatParticipantDto(
                    ownerId,
                    owner.getDisplayName(),
                    owner.getProfilePictureUrl(),
                    "owner",
                    "joined",
                    ownerId == userId));
        }
        Predicate<AiChatParticipant> visible = session.isOwnedByCurrentUser()
                ? participant -> true
                : participant -> "joined".equals(participant.getStatus());
        chatMapper.listParticipants(workspaceId, session.getId()).stream()
                .filter(visible)
                .filter(participant -> members.containsKey(participant.getUserId()))
                .map(participant -> participantDto(
                        participant,
                        members.get(participant.getUserId()),
                        participant.getUserId() == userId))
                .forEach(participants::add);
        return List.copyOf(participants);
    }

    private Map<Integer, User> activeMembers(int workspaceId) {
        return workspaceService.getMembers(workspaceId).stream()
                .collect(Collectors.toMap(
                        User::getId,
                        user -> user,
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    private AiChatParticipantDto participantDto(
            AiChatParticipant participant, User user, boolean currentUser) {
        return new AiChatParticipantDto(
                participant.getUserId(),
                user.getDisplayName(),
                user.getProfilePictureUrl(),
                "participant",
                participant.getStatus(),
                currentUser);
    }

    private AiChatPresenceDto presenceDto(
            int sessionId,
            List<AiChatParticipantDto> participants,
            AiChatPresenceRegistry.Snapshot snapshot) {
        Set<Integer> present = snapshot.presentUserIds();
        List<AiChatParticipantDto> live = participants.stream()
                .filter(participant -> present.contains(participant.userId()))
                .toList();
        Set<Integer> visibleIds = participants.stream()
                .map(AiChatParticipantDto::userId)
                .collect(Collectors.toUnmodifiableSet());
        List<Integer> typing = snapshot.typingUserIds().stream()
                .filter(visibleIds::contains)
                .sorted()
                .toList();
        return new AiChatPresenceDto(sessionId, live, typing);
    }

    private void requireSharedActive(AiChatSession session) {
        if (!SHARED.equals(session.getVisibility()) || !ACTIVE.equals(session.getStatus())) {
            throw new ConflictException("Assistant session is not open for participants");
        }
    }

    private AiChatStepFrameDto sessionFrame(int workspaceId, int sessionId, String status) {
        return new AiChatStepFrameDto(
                workspaceId, sessionId, 0, 0, "session", null, status, null);
    }

    private int currentWorkspaceId() {
        return workspaceService.getCurrentWorkspaceId();
    }

    private int currentUserId() {
        return authService.getCurrentUser().getId();
    }

    private List<Integer> activeMemberIds(int workspaceId, int userId) {
        List<Integer> activeMemberIds = workspaceService.getMembers(workspaceId).stream()
            .map(user -> user.getId())
            .toList();
        if (!activeMemberIds.contains(userId)) {
            throw inaccessible();
        }
        return activeMemberIds;
    }

    private void requireActiveAuthor(AiChatSession session, List<Integer> activeMemberIds) {
        if (session.getCreatedByUserId() == null
                || !activeMemberIds.contains(session.getCreatedByUserId())) {
            throw inaccessible();
        }
    }

    private void auditRetainedRead(AiChatSession session) {
        auditSessionRead(session, "retained");
    }

    private void auditSessionRead(AiChatSession session, String scope) {
        auditService.recordStrict(
            "ai.assistant.session.read",
            "ai_chat_session",
            session.getId(),
            "Assistant session " + session.getId(),
            "Administrative assistant session read",
            Map.of("scope", scope));
    }

    private void auditAdministrativeReads(
            int workspaceId, int userId, List<AiChatSession> sessions) {
        if (!workspaceService.permissionsFor(workspaceId, userId)
                .contains(Permission.AI_SESSION_ADMIN)) {
            return;
        }
        sessions.stream()
            .filter(session -> !Objects.equals(session.getCreatedByUserId(), userId))
            .forEach(session -> auditSessionRead(session, "accessible"));
    }

    private AiChatSession requireAccessible(int workspaceId, int userId, int id) {
        AiChatSession session = chatMapper.getAccessibleSessionById(workspaceId, userId, id);
        if (session == null) {
            throw inaccessible();
        }
        return session;
    }

    private AiChatSession requireSession(int workspaceId, int userId, int id) {
        AiChatSession session = chatMapper.getSessionById(workspaceId, userId, id);
        if (session == null) {
            throw inaccessible();
        }
        return session;
    }

    private AiChatSession requireLocked(int workspaceId, int userId, int id) {
        AiChatSession session = chatMapper.getSessionByIdForUpdate(workspaceId, userId, id);
        if (session == null) {
            throw inaccessible();
        }
        return session;
    }

    private AiChatSession requireOwnedLocked(int workspaceId, int userId, int id) {
        AiChatSession session = requireLocked(workspaceId, userId, id);
        if (!Objects.equals(session.getCreatedByUserId(), userId)) {
            throw inaccessible();
        }
        return session;
    }

    private void requireAppendAccess(int workspaceId, int userId, AiChatSession session) {
        if (Objects.equals(session.getCreatedByUserId(), userId)) {
            return;
        }
        if (!SHARED.equals(session.getVisibility())
                || !chatMapper.isParticipant(workspaceId, session.getId(), userId)) {
            throw inaccessible();
        }
    }

    private int pageOffset(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("Page must be positive and size must be between 1 and 100");
        }
        long offset = (long) (page - 1) * size;
        if (offset > MAX_PAGE_OFFSET) {
            throw new BadRequestException("Page offset exceeds the maximum allowed window");
        }
        return (int) offset;
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BadRequestException("Session title is required");
        }
        String normalized = title.trim();
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new BadRequestException("Session title is too long");
        }
        return normalized;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("Message content is required");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BadRequestException("Message content is too long");
        }
        return content;
    }

    private ForbiddenException inaccessible() {
        return new ForbiddenException(INACCESSIBLE);
    }
}
