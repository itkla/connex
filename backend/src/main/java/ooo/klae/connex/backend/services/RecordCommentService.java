package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.RecordComment;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.dto.UserReferenceDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-local record comment thread business logic and authorization. */
@Service
@RequiredArgsConstructor
public class RecordCommentService {

    private static final Logger log = LoggerFactory.getLogger(RecordCommentService.class);

    private static final int MAX_COMMENT_LENGTH = 5000;
    private static final int MAX_COMMENTS_PER_THREAD = 200;
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int SNIPPET_LENGTH = 140;
    private static final String MENTION_TYPE = "comment.mention";
    private static final String REPLY_TYPE = "comment.reply";
    private static final String CATEGORY = "comment";
    private static final String SEVERITY = "info";
    private static final String IN_APP = "in_app";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RecordCommentMapper recordCommentMapper;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ReferenceService referenceService;
    private final NotificationDelivery notificationDelivery;
    private final NotificationPreferenceService notificationPreferenceService;
    private final NotificationChangePublisher notificationChanges;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

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
        User actor = authService.getCurrentUser();
        int actorId = actor.getId();
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

        List<Integer> mentioned = referenceService.syncReferences(
            workspaceId,
            ReferenceService.SOURCE_COMMENT,
            commentSourceId(comment.getId()),
            content);
        recordCreateAudit(thread, comment);
        notifyCommentRecipients(workspaceId, thread, comment, actor, mentioned, List.of());
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
        User actor = authService.getCurrentUser();
        int actorId = actor.getId();
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
        if (ThreadState.RESOLVED.wire().equals(locked.getState())) {
            throw new ConflictException("Thread is resolved; reopen it before replying");
        }
        if (recordCommentMapper.countCommentsInThread(workspaceId, threadId) >= MAX_COMMENTS_PER_THREAD) {
            throw new BadRequestException("A comment thread may contain at most 200 comments");
        }
        List<Integer> participantIds = recordCommentMapper.getCommentsByThread(workspaceId, threadId)
            .stream()
            .map(RecordComment::getAuthorUserId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

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
        List<Integer> mentioned = referenceService.syncReferences(
            workspaceId,
            ReferenceService.SOURCE_COMMENT,
            commentSourceId(comment.getId()),
            content);
        recordCreateAudit(locked, comment);
        notifyCommentRecipients(
            workspaceId, locked, comment, actor, mentioned, participantIds);
        RecordComment inserted = recordCommentMapper.getCommentById(workspaceId, comment.getId());
        if (inserted == null) {
            throw new IllegalStateException("Inserted comment could not be reloaded");
        }
        return hydrateComment(workspaceId, inserted);
    }

    /** Soft-redacts a comment while retaining its immutable row and authorship. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.COMMENT_CREATE)
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
        referenceService.deleteReferences(
            workspaceId, ReferenceService.SOURCE_COMMENT, commentSourceId(commentId));
        auditService.record(
            "comment.delete",
            lockedThread.getTargetType(),
            lockedThread.getTargetId(),
            targetLabel(lockedThread),
            "Redacted record comment",
            Map.of("threadId", lockedThread.getId(), "commentId", commentId));
        notificationChanges.publish(
            workspaceId, ReferenceService.SOURCE_COMMENT, commentSourceId(commentId));
    }

    /** Resolves an open thread when its optimistic version still matches. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.COMMENT_CREATE)
    public RecordCommentThread resolve(long threadId, int expectedVersion) {
        return transitionThread(threadId, expectedVersion, ThreadState.RESOLVED);
    }

    /** Reopens a resolved thread when its optimistic version still matches. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.COMMENT_CREATE)
    public RecordCommentThread reopen(long threadId, int expectedVersion) {
        return transitionThread(threadId, expectedVersion, ThreadState.OPEN);
    }

    private RecordCommentThread transitionThread(
            long threadId,
            int expectedVersion,
            ThreadState requestedState) {
        requirePositiveId(threadId, "Thread");
        if (expectedVersion < 0) {
            throw new BadRequestException("Expected version cannot be negative");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        workspaceService.requirePermission(workspaceId, actorId, Permission.COMMENT_CREATE);
        RecordCommentThread initial = requireThread(workspaceId, threadId);
        requireTargetVisible(
            workspaceId, TargetType.parse(initial.getTargetType()), initial.getTargetId());

        RecordCommentThread locked = recordCommentMapper.getThreadByIdForUpdate(workspaceId, threadId);
        if (locked == null) {
            throw new ResourceNotFoundException("Comment thread not found with id: " + threadId);
        }
        workspaceService.requirePermission(workspaceId, actorId, Permission.COMMENT_CREATE);
        requireTargetVisible(
            workspaceId, TargetType.parse(locked.getTargetType()), locked.getTargetId());
        if (locked.getVersion() != expectedVersion) {
            throw new ConflictException("Comment thread changed; refresh and retry");
        }
        if (requestedState.wire().equals(locked.getState())) {
            return hydrateThread(workspaceId, locked);
        }

        int rows = requestedState == ThreadState.RESOLVED
            ? recordCommentMapper.resolveThread(workspaceId, threadId, actorId)
            : recordCommentMapper.reopenThread(workspaceId, threadId);
        if (rows != 1) {
            throw new ConflictException("Comment thread changed; refresh and retry");
        }
        RecordCommentThread updated = requireThread(workspaceId, threadId);
        String action = requestedState == ThreadState.RESOLVED
            ? "comment.resolve"
            : "comment.reopen";
        String summary = requestedState == ThreadState.RESOLVED
            ? "Resolved record comment thread"
            : "Reopened record comment thread";
        auditService.record(
            action,
            "comment_thread",
            threadEntityId(threadId),
            targetLabel(updated),
            summary,
            Map.of(
                "threadId", threadId,
                "targetType", updated.getTargetType(),
                "targetId", updated.getTargetId()));
        if (requestedState == ThreadState.RESOLVED) {
            notificationChanges.publish(
                workspaceId, ReferenceService.SOURCE_COMMENT, threadEntityId(threadId));
        }
        return hydrateThread(workspaceId, updated);
    }

    private void notifyCommentRecipients(
            int workspaceId,
            RecordCommentThread thread,
            RecordComment comment,
            User actor,
            List<Integer> mentionedIds,
            List<Integer> participantIds) {
        TargetType targetType = TargetType.parse(thread.getTargetType());
        if (!isTargetVisible(workspaceId, targetType, thread.getTargetId())) {
            return;
        }
        Set<Integer> mentionedRecipients = new LinkedHashSet<>(mentionedIds);
        mentionedRecipients.remove(actor.getId());
        Set<Integer> mentionWinners = new LinkedHashSet<>();
        for (int recipientId : mentionedRecipients) {
            if (notifyRecipient(
                    workspaceId, thread, comment, actor, recipientId, MENTION_TYPE)) {
                mentionWinners.add(recipientId);
            }
        }
        Set<Integer> replyRecipients = new LinkedHashSet<>(participantIds);
        replyRecipients.remove(actor.getId());
        replyRecipients.removeAll(mentionWinners);
        for (int recipientId : replyRecipients) {
            notifyRecipient(workspaceId, thread, comment, actor, recipientId, REPLY_TYPE);
        }
    }

    private boolean notifyRecipient(
            int workspaceId,
            RecordCommentThread thread,
            RecordComment comment,
            User actor,
            int recipientId,
            String type) {
        if (!workspaceService.isMemberIncludingPending(workspaceId, recipientId)
                || !notificationPreferenceService.isEnabled(recipientId, type, IN_APP)) {
            return false;
        }
        try {
            Notification notification = new Notification();
            notification.setWorkspaceId(workspaceId);
            notification.setRecipientId(recipientId);
            notification.setType(type);
            notification.setCategory(CATEGORY);
            notification.setSeverity(SEVERITY);
            notification.setTemplateVersion(1);
            notification.setTitle(MENTION_TYPE.equals(type) ? "New mention" : "New comment reply");
            notification.setBody(MENTION_TYPE.equals(type)
                ? actor.getDisplayName() + " mentioned you in a comment"
                : actor.getDisplayName() + " replied to a comment thread");
            notification.setActorId(actor.getId());
            notification.setActorLabel(actor.getDisplayName());
            notification.setSourceType(ReferenceService.SOURCE_COMMENT);
            notification.setSourceId(commentSourceId(comment.getId()));
            notification.setSourceLabel(snippet(comment.getContent()));
            notification.setContextType(thread.getTargetType());
            notification.setContextId(thread.getTargetId());
            notification.setActionUrl(actionUrl(thread, comment.getId()));
            notification.setDedupeKey(type + ":" + comment.getId() + ":" + recipientId);
            notification.setTriggeredAt(LocalDateTime.now(ZoneOffset.UTC).format(TS));
            notification.setData(json(Map.of(
                "threadId", thread.getId(),
                "commentId", comment.getId())));
            notificationDelivery.deliver(notification);
        } catch (RuntimeException exception) {
            log.warn(
                "Failed to deliver {} notification for comment {} to recipient {}: {}",
                type,
                comment.getId(),
                recipientId,
                exception.toString());
        }
        return true;
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
        hydrateReferences(workspaceId, comments);
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
        hydrateReferences(workspaceId, comments);
        thread.setComments(comments);
        return thread;
    }

    private RecordComment hydrateComment(int workspaceId, RecordComment comment) {
        hydrateAuthors(workspaceId, List.of(comment));
        hydrateReferences(workspaceId, List.of(comment));
        return comment;
    }

    private void hydrateReferences(int workspaceId, List<RecordComment> comments) {
        if (comments.isEmpty()) {
            return;
        }
        Map<Integer, List<EntityReference>> bySource = referenceService.referencesBySource(
            workspaceId,
            ReferenceService.SOURCE_COMMENT,
            comments.stream().map(comment -> commentSourceId(comment.getId())).toList());
        List<ReferenceService.ReaderVisibleContent> visible = referenceService.redactInvisibleNoteTargets(
            workspaceId,
            comments.stream()
                .map(comment -> new ReferenceService.ReaderVisibleContent(
                    comment.getContent(),
                    bySource.getOrDefault(commentSourceId(comment.getId()), List.of())))
                .toList());
        for (int index = 0; index < comments.size(); index++) {
            RecordComment comment = comments.get(index);
            ReferenceService.ReaderVisibleContent content = visible.get(index);
            comment.setContent(content.content());
            comment.setReferences(content.references());
        }
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

    private boolean isTargetVisible(int workspaceId, TargetType targetType, int targetId) {
        return switch (targetType) {
            case PERSON -> personMapper.exists(workspaceId, targetId);
            case COMPANY -> companyMapper.exists(workspaceId, targetId);
            case DEAL -> dealMapper.exists(workspaceId, targetId);
        };
    }

    private void requireTargetVisible(int workspaceId, TargetType targetType, int targetId) {
        if (!isTargetVisible(workspaceId, targetType, targetId)) {
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

    private static int commentSourceId(long commentId) {
        try {
            return Math.toIntExact(commentId);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Comment id exceeds the supported reference range", exception);
        }
    }

    private static int threadEntityId(long threadId) {
        try {
            return Math.toIntExact(threadId);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Comment thread id exceeds the supported audit range", exception);
        }
    }

    private static String actionUrl(RecordCommentThread thread, long commentId) {
        String segment = switch (TargetType.parse(thread.getTargetType())) {
            case PERSON -> "contacts";
            case COMPANY -> "companies";
            case DEAL -> "deals";
        };
        return "/records/" + segment + "/" + thread.getTargetId() + "?comment=" + commentId;
    }

    private static String snippet(String content) {
        String plain = ReferenceService.toPlainText(content).strip();
        return plain.length() > SNIPPET_LENGTH ? plain.substring(0, SNIPPET_LENGTH) : plain;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize notification data", exception);
        }
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
