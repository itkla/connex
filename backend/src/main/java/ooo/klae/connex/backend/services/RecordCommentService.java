package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.RecordComment;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.dto.UserReferenceDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-local record comment thread business logic and authorization. */
@Service
@RequiredArgsConstructor
public class RecordCommentService {

    private static final int MAX_COMMENT_LENGTH = 5000;
    private static final int MAX_COMMENTS_PER_THREAD = 200;
    private static final int MAX_PAGE_LIMIT = 100;

    private final RecordCommentMapper recordCommentMapper;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    /** Returns one bounded page of threads for a currently visible record. */
    public List<RecordCommentThread> listThreads(
            String targetTypeRaw,
            int targetId,
            String stateRaw,
            int limit,
            int offset) {
        TargetType targetType = TargetType.parse(targetTypeRaw);
        ThreadState state = ThreadState.parse(stateRaw);
        requireTargetId(targetId);
        requirePageBounds(limit, offset);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTargetVisible(workspaceId, targetType, targetId);
        List<RecordCommentThread> threads = recordCommentMapper.getThreadPage(
            workspaceId, targetType.wire(), targetId, state.wire(), limit, offset);
        return hydrateThreads(workspaceId, threads);
    }

    /** Counts threads for a currently visible record and state filter. */
    public long countThreads(String targetTypeRaw, int targetId, String stateRaw) {
        TargetType targetType = TargetType.parse(targetTypeRaw);
        ThreadState state = ThreadState.parse(stateRaw);
        requireTargetId(targetId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTargetVisible(workspaceId, targetType, targetId);
        return recordCommentMapper.countThreads(
            workspaceId, targetType.wire(), targetId, state.wire());
    }

    /** Returns one thread after revalidating current target visibility. */
    public RecordCommentThread getThread(long threadId) {
        requirePositiveId(threadId, "Thread");
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        RecordCommentThread thread = requireThread(workspaceId, threadId);
        requireTargetVisible(workspaceId, TargetType.parse(thread.getTargetType()), thread.getTargetId());
        return hydrateThread(workspaceId, thread);
    }

    /** Creates a thread and root comment atomically. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.COMMENT_CREATE)
    public RecordCommentThread createThread(
            String targetTypeRaw,
            int targetId,
            String contentRaw,
            String clientTokenRaw) {
        TargetType targetType = TargetType.parse(targetTypeRaw);
        requireTargetId(targetId);
        String content = requireContent(contentRaw);
        String clientToken = requireClientToken(clientTokenRaw);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        requireTargetVisible(workspaceId, targetType, targetId);

        RecordComment existing = recordCommentMapper.getCommentByClientToken(workspaceId, clientToken);
        if (existing != null) {
            return replayedThread(workspaceId, existing, targetType, targetId);
        }

        RecordCommentThread thread = new RecordCommentThread();
        thread.setWorkspaceId(workspaceId);
        thread.setTargetType(targetType.wire());
        thread.setTargetId(targetId);
        thread.setCreatedByUserId(actorId);
        thread.setState(ThreadState.OPEN.wire());
        recordCommentMapper.insertThread(thread);

        RecordComment comment = newComment(workspaceId, thread.getId(), actorId, content, clientToken);
        try {
            recordCommentMapper.insertComment(comment);
        } catch (DuplicateKeyException exception) {
            recordCommentMapper.deleteEmptyThread(workspaceId, thread.getId());
            RecordComment replay = recordCommentMapper.getCommentByClientToken(workspaceId, clientToken);
            if (replay == null) {
                throw exception;
            }
            return replayedThread(workspaceId, replay, targetType, targetId);
        }

        recordCreateAudit(thread, comment);
        return hydrateThread(workspaceId, requireThread(workspaceId, thread.getId()));
    }

    /** Appends an immutable reply after locking and reauthorizing the thread. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.COMMENT_CREATE)
    public RecordComment reply(long threadId, String contentRaw, String clientTokenRaw) {
        requirePositiveId(threadId, "Thread");
        String content = requireContent(contentRaw);
        String clientToken = requireClientToken(clientTokenRaw);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        RecordCommentThread initial = requireThread(workspaceId, threadId);
        TargetType targetType = TargetType.parse(initial.getTargetType());
        requireTargetVisible(workspaceId, targetType, initial.getTargetId());

        RecordCommentThread locked = recordCommentMapper.getThreadByIdForUpdate(workspaceId, threadId);
        if (locked == null) {
            throw new ResourceNotFoundException("Comment thread not found with id: " + threadId);
        }
        requireTargetVisible(workspaceId, TargetType.parse(locked.getTargetType()), locked.getTargetId());
        workspaceService.requirePermission(workspaceId, actorId, Permission.COMMENT_CREATE);

        RecordComment existing = recordCommentMapper.getCommentByClientToken(workspaceId, clientToken);
        if (existing != null) {
            requireReplayThread(existing, threadId);
            return hydrateComment(workspaceId, existing);
        }
        if (recordCommentMapper.countCommentsInThread(workspaceId, threadId) >= MAX_COMMENTS_PER_THREAD) {
            throw new BadRequestException("A comment thread may contain at most 200 comments");
        }

        RecordComment comment = newComment(workspaceId, threadId, actorId, content, clientToken);
        try {
            recordCommentMapper.insertComment(comment);
        } catch (DuplicateKeyException exception) {
            RecordComment replay = recordCommentMapper.getCommentByClientToken(workspaceId, clientToken);
            if (replay == null) {
                throw exception;
            }
            requireReplayThread(replay, threadId);
            return hydrateComment(workspaceId, replay);
        }
        recordCreateAudit(locked, comment);
        RecordComment inserted = recordCommentMapper.getCommentById(workspaceId, comment.getId());
        if (inserted == null) {
            throw new IllegalStateException("Inserted comment could not be reloaded");
        }
        return hydrateComment(workspaceId, inserted);
    }

    /** Soft-redacts a comment while retaining its immutable row and authorship. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteComment(long commentId) {
        requirePositiveId(commentId, "Comment");
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        RecordComment initialComment = requireComment(workspaceId, commentId);
        RecordCommentThread initialThread = requireThread(workspaceId, initialComment.getThreadId());
        requireTargetVisible(
            workspaceId,
            TargetType.parse(initialThread.getTargetType()),
            initialThread.getTargetId());
        if (!Objects.equals(initialComment.getAuthorUserId(), actorId)) {
            workspaceService.requirePermission(workspaceId, actorId, Permission.COMMENT_MODERATE);
        }

        RecordCommentThread lockedThread = recordCommentMapper.getThreadByIdForUpdate(
            workspaceId, initialComment.getThreadId());
        if (lockedThread == null) {
            throw new ResourceNotFoundException("Comment not found with id: " + commentId);
        }
        requireTargetVisible(
            workspaceId,
            TargetType.parse(lockedThread.getTargetType()),
            lockedThread.getTargetId());
        RecordComment lockedComment = recordCommentMapper.getCommentByIdForUpdate(workspaceId, commentId);
        if (lockedComment == null || lockedComment.getThreadId() != lockedThread.getId()) {
            throw new ResourceNotFoundException("Comment not found with id: " + commentId);
        }
        if (!Objects.equals(lockedComment.getAuthorUserId(), actorId)) {
            workspaceService.requirePermission(workspaceId, actorId, Permission.COMMENT_MODERATE);
        }
        if (lockedComment.getDeletedAt() != null) {
            return;
        }
        if (recordCommentMapper.softDeleteComment(workspaceId, commentId, actorId) != 1) {
            throw new ResourceNotFoundException("Comment not found with id: " + commentId);
        }
        auditService.record(
            "comment.delete",
            lockedThread.getTargetType(),
            lockedThread.getTargetId(),
            targetLabel(lockedThread),
            "Redacted record comment",
            Map.of("threadId", lockedThread.getId(), "commentId", commentId));
    }

    private RecordCommentThread replayedThread(
            int workspaceId,
            RecordComment existing,
            TargetType targetType,
            int targetId) {
        RecordCommentThread thread = requireThread(workspaceId, existing.getThreadId());
        if (!thread.getTargetType().equals(targetType.wire()) || thread.getTargetId() != targetId) {
            throw new BadRequestException("Client token is already used for another record comment");
        }
        requireTargetVisible(workspaceId, targetType, targetId);
        return hydrateThread(workspaceId, thread);
    }

    private List<RecordCommentThread> hydrateThreads(
            int workspaceId,
            List<RecordCommentThread> threads) {
        if (threads.isEmpty()) {
            return List.of();
        }
        List<Long> threadIds = threads.stream().map(RecordCommentThread::getId).toList();
        List<RecordComment> comments = recordCommentMapper.getCommentsByThreadIds(workspaceId, threadIds);
        hydrateAuthors(workspaceId, comments);
        Map<Long, List<RecordComment>> commentsByThread = comments.stream().collect(
            Collectors.groupingBy(
                RecordComment::getThreadId,
                LinkedHashMap::new,
                Collectors.toList()));
        threads.forEach(thread -> thread.setComments(
            commentsByThread.getOrDefault(thread.getId(), List.of())));
        return threads;
    }

    private RecordCommentThread hydrateThread(int workspaceId, RecordCommentThread thread) {
        List<RecordComment> comments = recordCommentMapper.getCommentsByThread(workspaceId, thread.getId());
        hydrateAuthors(workspaceId, comments);
        thread.setComments(comments);
        return thread;
    }

    private RecordComment hydrateComment(int workspaceId, RecordComment comment) {
        hydrateAuthors(workspaceId, List.of(comment));
        return comment;
    }

    private void hydrateAuthors(int workspaceId, List<RecordComment> comments) {
        List<Integer> authorIds = comments.stream()
            .map(RecordComment::getAuthorUserId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (authorIds.isEmpty()) {
            return;
        }
        Map<Integer, UserDisplayNameDto> names = userMapper.getDisplayNamesByIds(authorIds).stream()
            .collect(Collectors.toMap(UserDisplayNameDto::id, Function.identity()));
        Map<Integer, UserReferenceDto> activeReferences = userMapper
            .getActiveWorkspaceMemberReferencesByIds(workspaceId, authorIds)
            .stream()
            .collect(Collectors.toMap(UserReferenceDto::id, Function.identity()));
        for (RecordComment comment : comments) {
            UserDisplayNameDto name = names.get(comment.getAuthorUserId());
            UserReferenceDto activeReference = activeReferences.get(comment.getAuthorUserId());
            comment.setAuthorDisplayName(name == null ? null : name.displayName());
            comment.setAuthorProfilePictureUrl(
                activeReference == null ? null : activeReference.profilePictureUrl());
        }
    }

    private RecordCommentThread requireThread(int workspaceId, long threadId) {
        RecordCommentThread thread = recordCommentMapper.getThreadById(workspaceId, threadId);
        if (thread == null) {
            throw new ResourceNotFoundException("Comment thread not found with id: " + threadId);
        }
        return thread;
    }

    private RecordComment requireComment(int workspaceId, long commentId) {
        RecordComment comment = recordCommentMapper.getCommentById(workspaceId, commentId);
        if (comment == null) {
            throw new ResourceNotFoundException("Comment not found with id: " + commentId);
        }
        return comment;
    }

    private void requireTargetVisible(int workspaceId, TargetType targetType, int targetId) {
        boolean visible = switch (targetType) {
            case PERSON -> personMapper.exists(workspaceId, targetId);
            case COMPANY -> companyMapper.exists(workspaceId, targetId);
            case DEAL -> dealMapper.exists(workspaceId, targetId);
        };
        if (!visible) {
            throw new ResourceNotFoundException(
                "Record not found with type " + targetType.wire() + " and id: " + targetId);
        }
    }

    private void recordCreateAudit(RecordCommentThread thread, RecordComment comment) {
        auditService.record(
            "comment.create",
            thread.getTargetType(),
            thread.getTargetId(),
            targetLabel(thread),
            "Created record comment",
            Map.of("threadId", thread.getId(), "commentId", comment.getId()));
    }

    private static RecordComment newComment(
            int workspaceId,
            long threadId,
            int actorId,
            String content,
            String clientToken) {
        RecordComment comment = new RecordComment();
        comment.setWorkspaceId(workspaceId);
        comment.setThreadId(threadId);
        comment.setAuthorUserId(actorId);
        comment.setContent(content);
        comment.setClientToken(clientToken);
        return comment;
    }

    private static void requireReplayThread(RecordComment comment, long threadId) {
        if (comment.getThreadId() != threadId) {
            throw new BadRequestException("Client token is already used for another record comment");
        }
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("Comment content is required");
        }
        if (content.length() > MAX_COMMENT_LENGTH) {
            throw new BadRequestException("Comment content may contain at most 5000 characters");
        }
        return content;
    }

    private static String requireClientToken(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            throw new BadRequestException("Client token is required");
        }
        String normalized = clientToken.trim().toLowerCase(Locale.ROOT);
        try {
            UUID token = UUID.fromString(normalized);
            if (!token.toString().equals(normalized)) {
                throw new BadRequestException("Client token must be a UUID");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Client token must be a UUID");
        }
    }

    private static void requirePageBounds(int limit, int offset) {
        if (limit < 1 || limit > MAX_PAGE_LIMIT) {
            throw new BadRequestException(
                "Thread page limit must be between 1 and " + MAX_PAGE_LIMIT);
        }
        if (offset < 0) {
            throw new BadRequestException("Thread page offset cannot be negative");
        }
    }

    private static void requireTargetId(int targetId) {
        if (targetId < 1) {
            throw new BadRequestException("Target id must be positive");
        }
    }

    private static void requirePositiveId(long id, String label) {
        if (id < 1) {
            throw new BadRequestException(label + " id must be positive");
        }
    }

    private static String targetLabel(RecordCommentThread thread) {
        return thread.getTargetType() + ":" + thread.getTargetId();
    }

    private enum TargetType {
        PERSON,
        COMPANY,
        DEAL;

        private static TargetType parse(String raw) {
            if (raw == null) {
                throw new BadRequestException("Target type is required");
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException("Unknown record comment target type: " + raw);
            }
        }

        private String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private enum ThreadState {
        OPEN,
        RESOLVED,
        ALL;

        private static ThreadState parse(String raw) {
            String value = raw == null || raw.isBlank() ? OPEN.name() : raw;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException("Unknown record comment thread state: " + raw);
            }
        }

        private String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
