package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiChatCitationProjector;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiChatMessageCreateRequest;
import ooo.klae.connex.backend.dto.AiChatMessageDto;
import ooo.klae.connex.backend.dto.AiChatSessionCreateRequest;
import ooo.klae.connex.backend.dto.AiChatSessionDetailDto;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.dto.AiChatSessionUpdateRequest;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
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
        session.setTitleUserSet(request == null || !request.isAutoTitle());
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
        List<AiChatMessageDto> messages = storedMessages.stream()
            .map(message -> AiChatMessageDto.from(
                    message,
                    citations.getOrDefault(message.getId(), List.of()),
                    citationProjector.suggestions(message)))
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
        if (chatMapper.updateSession(workspaceId, id, title, status) != 1) {
            throw inaccessible();
        }
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
                && chatMapper.updateSession(workspaceId, id, null, ARCHIVED) != 1) {
            throw inaccessible();
        }
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
        return AiChatMessageDto.from(stored);
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
