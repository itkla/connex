package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordCommentReactionSummary;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;

class RecordCommentReactionTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;
    @Autowired RecordCommentMapper recordCommentMapper;
    @Autowired GlobalExceptionHandler exceptionHandler;

    @Test
    void toggleAddsThenRemovesTheCurrentUsersReaction() {
        long commentId = newCommentId();

        List<RecordCommentReactionSummary> added = recordCommentService.toggleReaction(
            commentId, "thumbs_up");
        List<RecordCommentReactionSummary> removed = recordCommentService.toggleReaction(
            commentId, "thumbs_up");

        assertEquals(1, added.size());
        assertEquals("thumbs_up", added.getFirst().getReaction());
        assertEquals(1, added.getFirst().getCount());
        assertTrue(added.getFirst().isReactedByMe());
        assertEquals(List.of(), removed);
        assertEquals(0, recordCommentMapper.deleteReaction(
            workspace.getId(), commentId, currentUser.getId(), "thumbs_up"));
    }

    @Test
    void reactionIsUniquePerUserAndReactionKey() {
        long commentId = newCommentId();
        recordCommentService.toggleReaction(commentId, "heart");

        assertThrows(DuplicateKeyException.class, () -> recordCommentMapper.insertReaction(
            workspace.getId(), commentId, currentUser.getId(), "heart"));
    }

    @Test
    void allSixKeysAreAcceptedAndAnUnknownKeyIsRejected() {
        long commentId = newCommentId();
        List<String> reactions = List.of(
            "thumbs_up", "thumbs_down", "heart", "celebrate", "eyes", "laugh");

        for (String reaction : reactions) {
            recordCommentService.toggleReaction(commentId, reaction);
        }

        assertEquals(reactions, recordCommentService.getThread(threadId(commentId))
            .getComments()
            .getFirst()
            .getReactions()
            .stream()
            .map(RecordCommentReactionSummary::getReaction)
            .toList());
        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> recordCommentService.toggleReaction(commentId, "confused"));
        assertEquals(HttpStatus.BAD_REQUEST,
            exceptionHandler.badRequest(exception).getStatusCode());
    }

    @Test
    void redactedCommentRetainsExistingSummaryButRejectsNewReactions() {
        long commentId = newCommentId();
        recordCommentService.toggleReaction(commentId, "eyes");

        recordCommentService.deleteComment(commentId);

        RecordCommentReactionSummary retained = recordCommentService
            .getThread(threadId(commentId))
            .getComments()
            .getFirst()
            .getReactions()
            .getFirst();
        assertEquals("eyes", retained.getReaction());
        assertEquals(1, retained.getCount());
        assertEquals(List.of(), recordCommentService.toggleReaction(commentId, "eyes"));
        assertThrows(BadRequestException.class,
            () -> recordCommentService.toggleReaction(commentId, "heart"));
    }

    @Test
    void summaryCountsUsersAndMarksOnlyTheCurrentUsersKeys() {
        long commentId = newCommentId();
        User other = newUser();
        recordCommentService.toggleReaction(commentId, "thumbs_up");
        recordCommentService.toggleReaction(commentId, "heart");
        authenticateAs(other, workspace.getId());
        recordCommentService.toggleReaction(commentId, "thumbs_up");

        Map<String, RecordCommentReactionSummary> summary = recordCommentService
            .getThread(threadId(commentId))
            .getComments()
            .getFirst()
            .getReactions()
            .stream()
            .collect(Collectors.toMap(
                RecordCommentReactionSummary::getReaction,
                Function.identity()));

        assertEquals(2, summary.get("thumbs_up").getCount());
        assertTrue(summary.get("thumbs_up").isReactedByMe());
        assertEquals(1, summary.get("heart").getCount());
        assertFalse(summary.get("heart").isReactedByMe());
    }

    @Test
    void reactionsNeverCreateNotifications() {
        User recipient = newUser();
        long before = notificationMapper.countPage(
            recipient.getId(), null, null, null, null);
        long commentId = newCommentId();

        recordCommentService.toggleReaction(commentId, "celebrate");

        assertEquals(before, notificationMapper.countPage(
            recipient.getId(), null, null, null, null));
    }

    private long newCommentId() {
        Person person = newPerson(newCompany());
        return recordCommentService.createThread(
            "person", person.getId(), "Reaction target", token())
            .getComments()
            .getFirst()
            .getId();
    }

    private long threadId(long commentId) {
        return recordCommentMapper.getCommentById(workspace.getId(), commentId).getThreadId();
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }
}
