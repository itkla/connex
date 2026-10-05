package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.tenant.Permission;

class RecordCommentRbacTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;
    @Autowired RoleService roleService;
    @Autowired WorkspaceService workspaceService;

    @Test
    void createWithoutCommentCreateIsForbidden() {
        Person person = newPerson(newCompany());
        User restricted = newUser();
        assignRole(restricted, List.of());
        authenticateAs(restricted, workspace.getId());

        assertThrows(ForbiddenException.class, () -> recordCommentService.createThread(
            "person", person.getId(), "Denied", token()));
    }

    @Test
    void reactionWithoutCommentCreateIsForbidden() {
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Reaction target", token());
        User restricted = newUser();
        assignRole(restricted, List.of());
        authenticateAs(restricted, workspace.getId());

        assertThrows(ForbiddenException.class, () -> recordCommentService.addReaction(
            thread.getComments().getFirst().getId(), "heart"));
    }

    @Test
    void nonAuthorWithoutCommentModerateCannotDelete() {
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Owner comment", token());
        User restricted = newUser();
        assignRole(restricted, List.of());
        authenticateAs(restricted, workspace.getId());

        assertThrows(ForbiddenException.class,
            () -> recordCommentService.deleteComment(thread.getComments().get(0).getId()));
    }

    @Test
    void authorWithoutCommentModerateCanDeleteOwnComment() {
        Person person = newPerson(newCompany());
        User author = newUser();
        assignRole(author, List.of(Permission.COMMENT_CREATE.name()));
        authenticateAs(author, workspace.getId());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Author comment", token());

        assertDoesNotThrow(
            () -> recordCommentService.deleteComment(thread.getComments().get(0).getId()));
    }

    private void assignRole(User user, List<String> permissions) {
        WorkspaceRole role = roleService.createRole(
            workspace.getId(), currentUser.getId(), "Comment role " + unique(), permissions);
        workspaceService.assignCustomRole(
            workspace.getId(), currentUser.getId(), user.getId(), role.getId());
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }
}
