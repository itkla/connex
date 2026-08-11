package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordComment;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;

class RecordCommentServiceTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;
    @Autowired RecordCommentMapper recordCommentMapper;
    @Autowired WorkspaceService workspaceService;

    @Test
    void createThreadAndReplyReturnAscendingHydratedComments() {
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Root", token());
        RecordComment reply = recordCommentService.reply(thread.getId(), "Reply", token());

        RecordCommentThread loaded = recordCommentService.getThread(thread.getId());

        assertEquals(2, loaded.getComments().size());
        assertEquals("Root", loaded.getComments().get(0).getContent());
        assertEquals("Reply", loaded.getComments().get(1).getContent());
        assertEquals(reply.getId(), loaded.getComments().get(1).getId());
        assertEquals(currentUser.getDisplayName(), loaded.getComments().get(0).getAuthorDisplayName());
    }

    @Test
    void contentAllowsFiveThousandCharactersAndRejectsMore() {
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "x".repeat(5000), token());

        assertEquals(5000, thread.getComments().get(0).getContent().length());
        assertThrows(BadRequestException.class, () -> recordCommentService.reply(
            thread.getId(), "x".repeat(5001), token()));
    }

    @Test
    void threadRejectsTheTwoHundredAndFirstComment() {
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Root", token());
        for (int index = 1; index < 200; index++) {
            RecordComment comment = new RecordComment();
            comment.setWorkspaceId(workspace.getId());
            comment.setThreadId(thread.getId());
            comment.setAuthorUserId(currentUser.getId());
            comment.setContent("Reply " + index);
            comment.setClientToken(token());
            recordCommentMapper.insertComment(comment);
        }

        assertEquals(200, recordCommentMapper.countCommentsInThread(workspace.getId(), thread.getId()));
        assertThrows(BadRequestException.class, () -> recordCommentService.reply(
            thread.getId(), "Reply 200", token()));
    }

    @Test
    void deleteRedactsContentAndRetainsTombstone() {
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Sensitive", token());
        long commentId = thread.getComments().get(0).getId();

        recordCommentService.deleteComment(commentId);

        RecordComment retained = recordCommentMapper.getCommentById(workspace.getId(), commentId);
        assertNotNull(retained);
        assertNull(retained.getContent());
        assertNotNull(retained.getDeletedAt());
        assertEquals(currentUser.getId(), retained.getDeletedByUserId());
    }

    @Test
    void clientTokenReplayReturnsTheExistingThreadAndReply() {
        Person person = newPerson(newCompany());
        String rootToken = token();
        RecordCommentThread created = recordCommentService.createThread(
            "person", person.getId(), "Root", rootToken);
        RecordCommentThread replayed = recordCommentService.createThread(
            "person", person.getId(), "Root", rootToken);
        String replyToken = token();
        RecordComment reply = recordCommentService.reply(created.getId(), "Reply", replyToken);
        RecordComment replyReplay = recordCommentService.reply(created.getId(), "Reply", replyToken);

        assertEquals(created.getId(), replayed.getId());
        assertEquals(created.getComments().get(0).getId(), replayed.getComments().get(0).getId());
        assertEquals(reply.getId(), replyReplay.getId());
        assertEquals(1, recordCommentMapper.countThreads(
            workspace.getId(), "person", person.getId(), "all"));
        assertEquals(2, recordCommentMapper.countCommentsInThread(workspace.getId(), created.getId()));
    }

    @Test
    void invisibleTargetIsNotFound() {
        WorkspaceMembershipDto other = workspaceService.createWorkspace(
            "Other " + unique(), currentUser.getId());
        Person foreign = new Person();
        foreign.setWorkspaceId(other.getId());
        foreign.setName("Foreign " + unique());
        personMapper.insert(foreign);
        authenticateAs(currentUser, workspace.getId());

        assertThrows(ResourceNotFoundException.class, () -> recordCommentService.createThread(
            "person", foreign.getId(), "Hidden", token()));
        assertThrows(ResourceNotFoundException.class, () -> recordCommentService.listThreads(
            "person", foreign.getId(), "all", 20, 0));
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }
}
