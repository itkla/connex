package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
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

/** Durable assistant chat CRUD, access policy, and per-session message sequencing. */
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

    /** Returns an ordered page of caller-owned and explicitly shared sessions. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public PageResponse<AiChatSessionDto> page(int page, int size) {
        int offset = pageOffset(page, size);
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        List<AiChatSessionDto> items = chatMapper.listAccessibleSessions(
            workspaceId, userId, size, offset).stream()
            .map(AiChatSessionDto::from)
            .toList();
        return new PageResponse<>(items, chatMapper.countAccessibleSessions(workspaceId, userId));
    }

    /** Creates a private active session owned by the authenticated caller. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatSessionDto create(AiChatSessionCreateRequest request) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
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
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public AiChatSessionDetailDto get(int id, int messagePage, int messageSize) {
        int offset = pageOffset(messagePage, messageSize);
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        AiChatSession session = requireAccessible(workspaceId, userId, id);
        List<AiChatMessageDto> messages = chatMapper.listMessages(
            workspaceId, id, messageSize, offset).stream()
            .map(AiChatMessageDto::from)
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
        AiChatSession session = requireLocked(workspaceId, userId, id);
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
        if (session.getCreatedByUserId() != userId) {
            throw inaccessible();
        }
        return session;
    }

    private void requireAppendAccess(int workspaceId, int userId, AiChatSession session) {
        if (session.getCreatedByUserId() == userId) {
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
