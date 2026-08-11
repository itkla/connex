package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordComment;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.ObjectMapper;

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

        assertThrows(ForbiddenException.class, () -> recordCommentService.toggleReaction(
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

    @Test
    void erasedAuthorRequiresModerationBeforeAndAfterThreadAndCommentLocks() {
        RecordCommentMapper mapper = mock(RecordCommentMapper.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        CompanyMapper companyMapper = mock(CompanyMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WorkspaceService localWorkspaceService = mock(WorkspaceService.class);
        AuditService auditService = mock(AuditService.class);
        RecordCommentService service = new RecordCommentService(
            mapper,
            personMapper,
            companyMapper,
            dealMapper,
            userMapper,
            localWorkspaceService,
            mock(AuthService.class),
            mock(ReferenceService.class),
            mock(NotificationDelivery.class),
            mock(NotificationPreferenceService.class),
            mock(NotificationChangePublisher.class),
            auditService,
            mock(ObjectMapper.class));
        RecordComment comment = new RecordComment();
        comment.setId(5L);
        comment.setWorkspaceId(7);
        comment.setThreadId(3L);
        comment.setAuthorUserId(null);
        RecordCommentThread thread = new RecordCommentThread();
        thread.setId(3L);
        thread.setWorkspaceId(7);
        thread.setTargetType("deal");
        thread.setTargetId(9);
        when(localWorkspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(localWorkspaceService.getCurrentUserId()).thenReturn(42);
        when(mapper.getCommentById(7, 5L)).thenReturn(comment);
        when(mapper.getThreadById(7, 3L)).thenReturn(thread);
        when(mapper.getThreadByIdForUpdate(7, 3L)).thenReturn(thread);
        when(mapper.getCommentByIdForUpdate(7, 5L)).thenReturn(comment);
        when(mapper.softDeleteComment(7, 5L, 42)).thenReturn(1);
        when(dealMapper.exists(7, 9)).thenReturn(true);

        service.deleteComment(5L);

        InOrder order = inOrder(localWorkspaceService, mapper);
        order.verify(localWorkspaceService)
            .requirePermission(7, 42, Permission.COMMENT_MODERATE);
        order.verify(mapper).getThreadByIdForUpdate(7, 3L);
        order.verify(mapper).getCommentByIdForUpdate(7, 5L);
        order.verify(localWorkspaceService)
            .requirePermission(7, 42, Permission.COMMENT_MODERATE);
        verify(mapper).softDeleteComment(7, 5L, 42);
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
