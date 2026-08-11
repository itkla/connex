package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AuditLogMapper;

class CommentResolutionTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;
    @Autowired AuditLogMapper auditLogMapper;

    @Test
    void resolveAndReopenPreserveContentAndAuditTransitions() {
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Content remains", token());
        User resolver = newUser();
        authenticateAs(resolver, workspace.getId());

        RecordCommentThread resolved = recordCommentService.resolve(thread.getId(), 0);

        assertEquals("resolved", resolved.getState());
        assertEquals(resolver.getId(), resolved.getResolvedByUserId());
        assertNotNull(resolved.getResolvedAt());
        assertEquals(1, resolved.getVersion());
        assertEquals("Content remains", resolved.getComments().getFirst().getContent());

        RecordCommentThread idempotent = recordCommentService.resolve(thread.getId(), 1);
        assertEquals(1, idempotent.getVersion());

        authenticateAs(currentUser, workspace.getId());
        RecordCommentThread reopened = recordCommentService.reopen(thread.getId(), 1);

        assertEquals("open", reopened.getState());
        assertNull(reopened.getResolvedByUserId());
        assertNull(reopened.getResolvedAt());
        assertEquals(2, reopened.getVersion());
        assertEquals("Content remains", reopened.getComments().getFirst().getContent());

        List<AuditLog> transitions = auditLogMapper.findRecent(workspace.getId(), 50, 0)
            .stream()
            .filter(audit -> "comment.resolve".equals(audit.getAction())
                || "comment.reopen".equals(audit.getAction()))
            .toList();
        assertEquals(2, transitions.size());
        assertTrue(transitions.stream().anyMatch(audit -> "comment.resolve".equals(audit.getAction())
            && resolver.getId() == audit.getActorId()));
        assertTrue(transitions.stream().anyMatch(audit -> "comment.reopen".equals(audit.getAction())
            && currentUser.getId() == audit.getActorId()));
        assertTrue(transitions.stream().allMatch(audit -> audit.getChanges() == null
            || !audit.getChanges().contains("Content remains")));
    }

    @Test
    void staleVersionTransitionsConflict() {
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Conflict content", token());
        recordCommentService.resolve(thread.getId(), 0);

        assertThrows(
            ConflictException.class,
            () -> recordCommentService.reopen(thread.getId(), 0));
        assertThrows(
            ConflictException.class,
            () -> recordCommentService.resolve(thread.getId(), 0));
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }
}
