package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;

@ExtendWith(MockitoExtension.class)
class MemberScopeResolverTest {
    @Mock private WorkspaceService workspaceService;
    @InjectMocks private MemberScopeResolver resolver;

    @Test
    void absentAndBlankScopesResolveToAllTeam() {
        assertEquals(MemberScope.Mode.ALL_TEAM, resolver.resolve(null, null, 7).mode());
        assertEquals(MemberScope.Mode.ALL_TEAM, resolver.resolve("  ", List.of(99), 7).mode());

        verify(workspaceService, never()).getCurrentWorkspaceId();
    }

    @Test
    void meUsesOnlyTheServerResolvedUserId() {
        MemberScope scope = resolver.resolve("me", List.of(99), 7);

        assertEquals(MemberScope.Mode.ME, scope.mode());
        assertEquals(7, scope.userId());
        assertEquals(List.of(), scope.memberIds());
        verify(workspaceService, never()).getCurrentWorkspaceId();
    }

    @Test
    void membersAreDeduplicatedAndValidatedAsActive() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(11);
        when(workspaceService.getMembers(11)).thenReturn(List.of(user(3), user(5)));

        MemberScope scope = resolver.resolve("members", List.of(3, 5, 3), 7);

        assertEquals(MemberScope.Mode.MEMBERS, scope.mode());
        assertEquals(List.of(3, 5), scope.memberIds());
    }

    @Test
    void membersRejectInactiveOrForeignIds() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(11);
        when(workspaceService.getMembers(11)).thenReturn(List.of(user(3)));

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> resolver.resolve("members", List.of(3, 5), 7));

        assertEquals("memberIds must contain only active members of the current workspace",
            exception.getMessage());
    }

    @Test
    void invalidMemberListsAndScopesFailClosed() {
        assertThrows(BadRequestException.class,
            () -> resolver.resolve("members", null, 7));
        assertThrows(BadRequestException.class,
            () -> resolver.resolve("members", List.of(0), 7));
        assertThrows(BadRequestException.class,
            () -> resolver.resolve("members", IntStream.rangeClosed(1, 51).boxed().toList(), 7));
        assertThrows(BadRequestException.class,
            () -> resolver.resolve("everyone", null, 7));
    }

    private User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
