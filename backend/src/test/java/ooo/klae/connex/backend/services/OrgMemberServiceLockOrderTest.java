package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

@ExtendWith(MockitoExtension.class)
class OrgMemberServiceLockOrderTest {
    @Mock private OrgMemberMapper orgMemberMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private UserMapper userMapper;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;

    @InjectMocks private OrgMemberService service;

    @Test
    void setMemberLocksOrganizationAndTargetUserBeforeOwnerRowsAndUpsert() {
        User target = targetUser();
        when(orgMemberMapper.getRole(7, 1)).thenReturn("owner");
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(userMapper.lockById(9)).thenReturn(9);
        when(userMapper.getUserById(9)).thenReturn(target);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(1));

        service.setMember(7, 1, 9, "admin");

        InOrder order = inOrder(organizationMapper, userMapper, orgMemberMapper);
        order.verify(orgMemberMapper).getRole(7, 1);
        order.verify(organizationMapper).lockById(7);
        order.verify(userMapper).lockById(9);
        order.verify(userMapper).getUserById(9);
        order.verify(orgMemberMapper).lockOwnerIds(7);
        order.verify(orgMemberMapper).addMember(7, 9, "admin");
    }

    @Test
    void removeMemberLocksOrganizationBeforeOwnerRowsAndDelete() {
        when(orgMemberMapper.getRole(7, 1)).thenReturn("owner");
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(1));
        when(orgMemberMapper.removeMember(7, 9)).thenReturn(1);

        service.removeMember(7, 1, 9);

        InOrder order = inOrder(organizationMapper, orgMemberMapper);
        order.verify(orgMemberMapper).getRole(7, 1);
        order.verify(organizationMapper).lockById(7);
        order.verify(orgMemberMapper).lockOwnerIds(7);
        order.verify(orgMemberMapper).removeMember(7, 9);
    }

    @Test
    void setMemberRevalidatesCurrentOwnerAuthorityBeforeWriting() {
        User target = targetUser();
        when(orgMemberMapper.getRole(7, 1)).thenReturn("owner");
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(userMapper.lockById(9)).thenReturn(9);
        when(userMapper.getUserById(9)).thenReturn(target);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(2));

        assertThrows(ForbiddenException.class, () -> service.setMember(7, 1, 9, "admin"));

        verify(orgMemberMapper, never()).addMember(7, 9, "admin");
        verifyNoInteractions(auditService);
    }

    @Test
    void removeMemberRevalidatesCurrentOwnerAuthorityBeforeWriting() {
        when(orgMemberMapper.getRole(7, 1)).thenReturn("owner");
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(2));

        assertThrows(ForbiddenException.class, () -> service.removeMember(7, 1, 9));

        verify(orgMemberMapper, never()).removeMember(7, 9);
        verifyNoInteractions(auditService);
    }

    private static User targetUser() {
        User target = new User();
        target.setId(9);
        target.setDisplayName("Target");
        return target;
    }
}
