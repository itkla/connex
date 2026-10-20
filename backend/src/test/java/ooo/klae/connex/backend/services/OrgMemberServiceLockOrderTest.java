package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaConfinementAcknowledgementRequiredException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PrivilegedCredentialAttestationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

@ExtendWith(MockitoExtension.class)
class OrgMemberServiceLockOrderTest {
    private static final String NOT_A_TENANT =
            "Only active members of a workspace in this organization can be added to it";

    @Mock private OrgMemberMapper orgMemberMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private UserMapper userMapper;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private RegistrationVerificationService registrationVerificationService;
    @Mock private PrivilegedCredentialAttestationMapper attestationMapper;
    @Mock private PrivilegedAccountService privilegedAccountService;

    @org.junit.jupiter.api.BeforeEach
    void ownershipTransactionContext() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
            java.sql.Connection.TRANSACTION_READ_COMMITTED);
    }

    @org.junit.jupiter.api.AfterEach
    void clearOwnershipTransactionContext() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
    }

    @Mock private OwnershipOfferWriter ownershipOfferWriter;
    @InjectMocks private OrgMemberService service;

    @Test
    void finalErasureRetainsAdminMembershipsAndChecksCurrentCompletenessAfterTheRootUnion() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(orgMemberMapper.orgIdsOwnedBy(9)).thenReturn(List.of(7));
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(9, 12));
        when(orgMemberMapper.lockAccountMembershipOrgIds(9)).thenReturn(List.of(3, 7));

        service.assertAccountDeletionOrganizationRecovery(9, List.of(), List.of(7, 3));

        InOrder order = inOrder(organizationMapper, orgMemberMapper);
        order.verify(organizationMapper).lockByIdForShare(3);
        order.verify(organizationMapper).lockById(7);
        order.verify(orgMemberMapper).lockOwnerIds(7);
        order.verify(orgMemberMapper).lockExactRole(3, 9);
        order.verify(orgMemberMapper).lockAccountMembershipOrgIds(9);
        verify(orgMemberMapper, never()).lockExactRole(7, 9);
    }

    @Test
    void finalErasureRefusesAnUnplannedOrganizationMembership() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(orgMemberMapper.lockAccountMembershipOrgIds(9)).thenReturn(List.of(3, 7));

        assertThrows(ooo.klae.connex.backend.exceptions.ConflictException.class,
            () -> service.assertAccountDeletionOrganizationRecovery(9, List.of(3), List.of(3)));

        verifyNoInteractions(auditService);
    }

    @Test
    void setMemberLocksOrganizationAndSortedUsersBeforeOwnerRowsAndUpsert() {
        User target = targetUser(1);
        when(orgMemberMapper.getRole(7, 9)).thenReturn("owner");
        when(organizationMapper.lockActiveById(7)).thenReturn(7);
        when(userMapper.lockByIdForShare(1)).thenReturn(1);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(userMapper.getUserByIdForShare(1)).thenReturn(target);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(9));
        when(orgMemberMapper.isEligibleForMembership(7, 1)).thenReturn(true);
        when(orgMemberMapper.isActiveWorkspaceTenant(7, 1)).thenReturn(true);

        service.setMember(7, 9, 1, "admin");

        InOrder order = inOrder(organizationMapper, userMapper, orgMemberMapper);
        order.verify(orgMemberMapper).getRole(7, 9);
        order.verify(orgMemberMapper).isEligibleForMembership(7, 1);
        order.verify(userMapper).lockByIdForShare(1);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(organizationMapper).lockActiveById(7);
        order.verify(userMapper).getUserByIdForShare(1);
        order.verify(orgMemberMapper).lockOwnerIds(7);
        order.verify(orgMemberMapper).getRoleForUpdate(7, 1);
        order.verify(orgMemberMapper).isActiveWorkspaceTenant(7, 1);
        order.verify(orgMemberMapper).addMemberClearingFounder(7, 1, "admin");
        verify(orgMemberMapper, never()).addMember(7, 1, "admin");
    }

    /**
     * The confinement baseline is read in this organization after every lock and before the write;
     * the check runs after the write and before the success audit, carrying the request's
     * acknowledgement (#1534).
     */
    @Test
    void theConfinementCheckBracketsTheWriteAndPrecedesTheSuccessAudit() {
        stubAddableTarget();
        ConfinementBaseline baseline = new ConfinementBaseline(7, List.of(1), Set.of());
        when(privilegedAccountService.confinementBaseline(any(), eq(List.of(1)))).thenReturn(baseline);

        service.setMember(7, 9, 1, "admin", true);

        ArgumentCaptor<IntSupplier> organization = ArgumentCaptor.forClass(IntSupplier.class);
        InOrder order = inOrder(orgMemberMapper, privilegedAccountService, auditService);
        order.verify(orgMemberMapper).isActiveWorkspaceTenant(7, 1);
        order.verify(privilegedAccountService).confinementBaseline(organization.capture(), eq(List.of(1)));
        order.verify(orgMemberMapper).addMemberClearingFounder(7, 1, "admin");
        order.verify(privilegedAccountService).requireConfinementAcknowledged(baseline, true);
        order.verify(auditService).record("org.member.set", "organization", 7, "Target",
                "Set Target to org admin", null);
        assertEquals(7, organization.getValue().getAsInt());
    }

    /** A refused confinement writes no success audit; the transaction rolls the write back (#1534). */
    @Test
    void aRefusedConfinementWritesNoSuccessAudit() {
        stubAddableTarget();
        doThrow(new PrivilegedMfaConfinementAcknowledgementRequiredException(1))
                .when(privilegedAccountService).requireConfinementAcknowledged(any(), eq(false));

        assertThrows(PrivilegedMfaConfinementAcknowledgementRequiredException.class,
                () -> service.setMember(7, 9, 1, "admin"));

        verify(orgMemberMapper).addMemberClearingFounder(7, 1, "admin");
        verifyNoInteractions(auditService);
    }

    /** An owner re-roling themselves keeps any founder attribution they hold (#1534). */
    @Test
    void anOwnerChangingTheirOwnRoleKeepsTheirFounderAttribution() {
        User self = targetUser(9);
        when(orgMemberMapper.getRole(7, 9)).thenReturn("owner");
        when(organizationMapper.lockActiveById(7)).thenReturn(7);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(userMapper.getUserByIdForShare(9)).thenReturn(self);
        when(orgMemberMapper.getRoleForUpdate(7, 9)).thenReturn("owner");
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(2, 9));
        when(orgMemberMapper.isEligibleForMembership(7, 9)).thenReturn(true);

        service.setMember(7, 9, 9, "admin");

        verify(orgMemberMapper).addMember(7, 9, "admin");
        verify(orgMemberMapper, never()).addMemberClearingFounder(7, 9, "admin");
    }

    /** Founding flags the owner as founder before covering the owner's existing passkeys (#1534). */
    @Test
    void foundingAnOrganizationFlagsTheFounderAndCoversTheirExistingPasskeys() {
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(attestationMapper.insertFoundingCoverage(7, 9)).thenReturn(2);

        assertEquals(2, service.addFoundingOwner(7, 9));

        InOrder order = inOrder(orgMemberMapper, attestationMapper);
        order.verify(orgMemberMapper).addFoundingMember(7, 9);
        order.verify(attestationMapper).insertFoundingCoverage(7, 9);
        verify(orgMemberMapper, never()).addMember(7, 9, "owner");
    }

    @Test
    void removeMemberLocksSortedUsersAndOrganizationBeforeOwnerRowsAndDelete() {
        when(orgMemberMapper.getRole(7, 1)).thenReturn("owner");
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(userMapper.lockByIdForShare(1)).thenReturn(1);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(1));
        when(orgMemberMapper.removeMember(7, 9)).thenReturn(1);

        service.removeMember(7, 1, 9);

        InOrder order = inOrder(organizationMapper, userMapper, orgMemberMapper);
        order.verify(orgMemberMapper).getRole(7, 1);
        order.verify(userMapper).lockByIdForShare(1);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(organizationMapper).lockById(7);
        order.verify(orgMemberMapper).lockOwnerIds(7);
        order.verify(orgMemberMapper).removeMember(7, 9);
    }

    @Test
    void setMemberRevalidatesCurrentOwnerAuthorityBeforeWriting() {
        User target = targetUser(9);
        when(orgMemberMapper.getRole(7, 1)).thenReturn("owner");
        when(organizationMapper.lockActiveById(7)).thenReturn(7);
        when(userMapper.lockByIdForShare(1)).thenReturn(1);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(userMapper.getUserByIdForShare(9)).thenReturn(target);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(2));
        when(orgMemberMapper.isEligibleForMembership(7, 9)).thenReturn(true);

        assertThrows(ForbiddenException.class, () -> service.setMember(7, 1, 9, "admin"));

        verify(orgMemberMapper, never()).addMember(7, 9, "admin");
        verify(orgMemberMapper, never()).addMemberClearingFounder(7, 9, "admin");
        verifyNoInteractions(auditService);
    }

    @Test
    void removeMemberRevalidatesCurrentOwnerAuthorityBeforeWriting() {
        when(orgMemberMapper.getRole(7, 1)).thenReturn("owner");
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(userMapper.lockByIdForShare(1)).thenReturn(1);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(2));

        assertThrows(ForbiddenException.class, () -> service.removeMember(7, 1, 9));

        verify(orgMemberMapper, never()).removeMember(7, 9);
        verifyNoInteractions(auditService);
    }

    /**
     * Tenancy is revalidated under the locks, before mailbox proof is consulted: a target that was
     * a tenant when the add began but is not any more is refused like any outsider (#1534).
     */
    @Test
    void tenancyIsRevalidatedUnderTheLocksBeforeMailboxProof() {
        User target = targetUser(1);
        when(orgMemberMapper.isEligibleForMembership(7, 1)).thenReturn(true);
        when(orgMemberMapper.getRole(7, 9)).thenReturn("owner");
        when(organizationMapper.lockActiveById(7)).thenReturn(7);
        when(userMapper.lockByIdForShare(1)).thenReturn(1);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(userMapper.getUserByIdForShare(1)).thenReturn(target);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(9));

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.setMember(7, 9, 1, "admin"));

        assertEquals(NOT_A_TENANT, refused.getMessage());
        verifyNoInteractions(registrationVerificationService, auditService);
        verify(orgMemberMapper, never()).addMemberClearingFounder(7, 1, "admin");
    }

    /**
     * An unknown account and an outsider answer the same organization-scoped read and are refused
     * before any lock is taken, so neither the refusal nor its cost tells them apart (#1534).
     */
    @Test
    void anIneligibleTargetIsRefusedBeforeAnyLock() {
        when(orgMemberMapper.getRole(7, 9)).thenReturn("owner");

        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.setMember(7, 9, 1, "admin"));

        assertEquals(NOT_A_TENANT, refused.getMessage());
        verify(orgMemberMapper).isEligibleForMembership(7, 1);
        verify(userMapper, never()).lockByIdForShare(anyInt());
        verifyNoInteractions(organizationMapper, auditService);
    }

    /**
     * An add by email checks the role before looking anyone up, then resolves the address through
     * the same organization-scoped read, so a malformed role or an ineligible address answers alike
     * whether or not the address has an account (#1534).
     */
    @Test
    void anAddByEmailChecksTheRoleFirstAndRefusesAnIneligibleAddressBeforeAnyLock() {
        when(orgMemberMapper.getRole(7, 9)).thenReturn("owner");

        BadRequestException badRole = assertThrows(BadRequestException.class,
                () -> service.setMemberByEmail(7, 9, "someone@example.com", "bogus"));
        assertEquals("Unknown org role: bogus", badRole.getMessage());
        verify(orgMemberMapper, never()).findEligibleUserIdByEmail(anyInt(), any());
        when(orgMemberMapper.findEligibleUserIdByEmail(7, "someone@example.com")).thenReturn(null);

        BadRequestException ineligible = assertThrows(BadRequestException.class,
                () -> service.setMemberByEmail(7, 9, "Someone@Example.com ", "admin"));

        assertEquals(NOT_A_TENANT, ineligible.getMessage());
        verify(orgMemberMapper).findEligibleUserIdByEmail(7, "someone@example.com");
        verify(userMapper, never()).getUserByEmail(any());
        verify(userMapper, never()).lockByIdForShare(anyInt());
        verifyNoInteractions(organizationMapper, auditService);
    }

    /** No membership is created in or changed for an organization whose teardown has begun (#1534). */
    @Test
    void setMemberLocksOnlyAnActiveOrganization() {
        when(orgMemberMapper.isEligibleForMembership(7, 1)).thenReturn(true);
        when(orgMemberMapper.getRole(7, 9)).thenReturn("owner");
        when(userMapper.lockByIdForShare(1)).thenReturn(1);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(organizationMapper.lockActiveById(7)).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service.setMember(7, 9, 1, "admin"));

        verify(organizationMapper, never()).lockById(anyInt());
        verify(orgMemberMapper, never()).lockOwnerIds(anyInt());
        verifyNoInteractions(auditService);
    }

    /**
     * Leaving an organization takes the caller's account shared, the organization exclusively
     * while active, the owner rows and the caller's own row before deleting it, then audits
     * strictly (#1534).
     */
    @Test
    void leaveLocksInSetMembersOrderBeforeDeletingAndAuditsStrictly() {
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(organizationMapper.lockActiveById(7)).thenReturn(7);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(2, 9));
        when(orgMemberMapper.getRoleForUpdate(7, 9)).thenReturn("owner");

        service.leave(7, 9);

        InOrder order = inOrder(userMapper, organizationMapper, orgMemberMapper, auditService);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(organizationMapper).lockActiveById(7);
        order.verify(orgMemberMapper).lockOwnerIds(7);
        order.verify(orgMemberMapper).getRoleForUpdate(7, 9);
        order.verify(orgMemberMapper).removeMember(7, 9);
        order.verify(auditService).recordStrictScoped("org.member.leave", "organization", 7, null, 7,
                null, "Left the organization", null);
        verifyNoInteractions(sessionSecurityService);
    }

    @Test
    void theLastOwnerCannotLeave() {
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(organizationMapper.lockActiveById(7)).thenReturn(7);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(9));
        when(orgMemberMapper.getRoleForUpdate(7, 9)).thenReturn("owner");

        assertThrows(BadRequestException.class, () -> service.leave(7, 9));

        verify(orgMemberMapper, never()).removeMember(anyInt(), anyInt());
        verifyNoInteractions(auditService);
    }

    @Test
    void onlyAMemberOfAnActiveOrganizationCanLeave() {
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(organizationMapper.lockActiveById(7)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.leave(7, 9));
        verify(orgMemberMapper, never()).lockOwnerIds(anyInt());

        when(organizationMapper.lockActiveById(7)).thenReturn(7);
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(2));
        when(orgMemberMapper.getRoleForUpdate(7, 9)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.leave(7, 9));

        verify(orgMemberMapper, never()).removeMember(anyInt(), anyInt());
        verifyNoInteractions(auditService);
    }

    @Test
    void accountGuardLocksUserAndSortedOrganizationsBeforeOwnerRows() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(orgMemberMapper.orgIdsOwnedBy(9)).thenReturn(List.of(7, 3, 7));
        when(organizationMapper.lockById(3)).thenReturn(3);
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(organizationMapper.lockByIdForShare(5)).thenReturn(5);
        when(organizationMapper.lockByIdForShare(11)).thenReturn(11);
        when(orgMemberMapper.lockOwnerIds(3)).thenReturn(List.of(9, 11));
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(9, 12));

        service.assertNotSoleOwnerOfAnyOrg(9, List.of(11, 5, 3));

        InOrder order = inOrder(userMapper, organizationMapper, orgMemberMapper);
        order.verify(userMapper).lockById(9);
        order.verify(orgMemberMapper).orgIdsOwnedBy(9);
        order.verify(organizationMapper).lockById(3);
        order.verify(organizationMapper).lockByIdForShare(5);
        order.verify(organizationMapper).lockById(7);
        order.verify(organizationMapper).lockByIdForShare(11);
        order.verify(orgMemberMapper).lockOwnerIds(3);
        order.verify(orgMemberMapper).lockOwnerIds(7);
    }

    private void stubAddableTarget() {
        when(orgMemberMapper.getRole(7, 9)).thenReturn("owner");
        when(orgMemberMapper.isEligibleForMembership(7, 1)).thenReturn(true);
        when(userMapper.lockByIdForShare(1)).thenReturn(1);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(organizationMapper.lockActiveById(7)).thenReturn(7);
        when(userMapper.getUserByIdForShare(1)).thenReturn(targetUser(1));
        when(orgMemberMapper.lockOwnerIds(7)).thenReturn(List.of(9));
        when(orgMemberMapper.isActiveWorkspaceTenant(7, 1)).thenReturn(true);
    }

    private static User targetUser(int userId) {
        User target = new User();
        target.setId(userId);
        target.setDisplayName("Target");
        return target;
    }
}
