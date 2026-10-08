package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.PrivilegedMfaAttestationGrant;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MfaAttestationCodeDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PrivilegedCredentialAttestationMapper;
import ooo.klae.connex.backend.mappers.PrivilegedMfaAttestationGrantMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.util.MfaAttestationCode;

@ExtendWith(MockitoExtension.class)
class PrivilegedMfaAttestationServiceTest {
    private static final int ORG_ID = 3;
    private static final int WORKSPACE_ID = 5;
    private static final int GRANTOR_ID = 7;
    private static final int GRANTEE_ID = 9;
    private static final long GRANT_ID = 41L;
    private static final int PASSKEY_ROW_ID = 55;
    private static final int SESSION_EPOCH = 4;
    private static final String CODE = "0123-4567-89AB-CDEF";

    @Mock private WorkspaceService workspaceService;
    @Mock private OrgMemberService orgMemberService;
    @Mock private PrivilegedMfaAttestationGrantMapper grantMapper;
    @Mock private PrivilegedCredentialAttestationMapper attestationMapper;
    @Mock private WebauthnCredentialMapper credentialMapper;
    @Mock private UserMapper userMapper;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private AuditService auditService;

    @InjectMocks private PrivilegedMfaAttestationService service;

    @Test
    void issuingThroughAWorkspaceStepsUpLocksSupersedesAndAuditsWithoutTheCode() {
        when(workspaceService.lockAttestationAuthority(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID)).thenReturn(ORG_ID);
        when(grantMapper.revokeOpen(GRANTEE_ID, ORG_ID, GRANTOR_ID)).thenReturn(1);
        stubStoredGrant();
        when(userMapper.getUserById(GRANTEE_ID)).thenReturn(user(GRANTEE_ID));

        MfaAttestationCodeDto issued = service.issueForWorkspace(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID);

        assertTrue(issued.code().matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}"));
        assertEquals("2026-10-08T12:00:00.123456Z", issued.expiresAt());
        ArgumentCaptor<PrivilegedMfaAttestationGrant> stored =
            ArgumentCaptor.forClass(PrivilegedMfaAttestationGrant.class);
        ArgumentCaptor<Object> details = ArgumentCaptor.forClass(Object.class);
        InOrder order = inOrder(sessionSecurityService, workspaceService, grantMapper, auditService);
        order.verify(sessionSecurityService).requireRecentAuthentication(GRANTOR_ID);
        order.verify(workspaceService).lockAttestationAuthority(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID);
        order.verify(grantMapper).revokeOpen(GRANTEE_ID, ORG_ID, GRANTOR_ID);
        order.verify(grantMapper).insert(stored.capture());
        order.verify(auditService).recordStrictScoped(
            eq("auth.mfa.attestation.issued"), eq("user"), eq(GRANTEE_ID), eq(WORKSPACE_ID), eq(ORG_ID),
            eq("Grantee"), eq("Issued a privileged MFA attestation code"), details.capture());
        PrivilegedMfaAttestationGrant grant = stored.getValue();
        assertEquals(ORG_ID, grant.getOrgId());
        assertEquals(WORKSPACE_ID, grant.getWorkspaceId());
        assertEquals(GRANTOR_ID, grant.getGrantorUserId());
        assertEquals(GRANTEE_ID, grant.getGranteeUserId());
        assertEquals(
            MfaAttestationCode.digest(GRANTEE_ID, MfaAttestationCode.normalize(issued.code())),
            grant.getCodeDigest());
        assertFalse(details.getValue().toString().contains(issued.code().replace("-", "")));
        assertFalse(details.getValue().toString().contains(issued.code()));
        assertEquals(Map.of("grantId", GRANT_ID, "grantorUserId", GRANTOR_ID,
            "expiresAt", "2026-10-08T12:00:00.123456Z", "superseded", 1), details.getValue());
    }

    @Test
    void anOrganizationOwnersCodeTakesTheOrgAuthorityAndNamesNoWorkspace() {
        stubStoredGrant();

        service.issueForOrganization(ORG_ID, GRANTOR_ID, GRANTEE_ID);

        ArgumentCaptor<PrivilegedMfaAttestationGrant> stored =
            ArgumentCaptor.forClass(PrivilegedMfaAttestationGrant.class);
        InOrder order = inOrder(sessionSecurityService, orgMemberService, grantMapper);
        order.verify(sessionSecurityService).requireRecentAuthentication(GRANTOR_ID);
        order.verify(orgMemberService).lockAttestationAuthority(ORG_ID, GRANTOR_ID, GRANTEE_ID);
        order.verify(grantMapper).insert(stored.capture());
        assertNull(stored.getValue().getWorkspaceId());
        assertEquals(ORG_ID, stored.getValue().getOrgId());
        verifyNoInteractions(workspaceService);
    }

    @Test
    void nobodyCanIssueOrRevokeACodeForThemselves() {
        assertThrows(ForbiddenException.class,
            () -> service.issueForWorkspace(WORKSPACE_ID, GRANTOR_ID, GRANTOR_ID));
        assertThrows(ForbiddenException.class,
            () -> service.issueForOrganization(ORG_ID, GRANTOR_ID, GRANTOR_ID));
        assertThrows(ForbiddenException.class,
            () -> service.revokeForWorkspace(WORKSPACE_ID, GRANTOR_ID, GRANTOR_ID));

        verifyNoInteractions(sessionSecurityService, workspaceService, orgMemberService, grantMapper);
    }

    @Test
    void aMissingStepUpRefusesBeforeAnyLock() {
        doThrow(new RecentAuthenticationRequiredException())
            .when(sessionSecurityService).requireRecentAuthentication(GRANTOR_ID);

        assertThrows(RecentAuthenticationRequiredException.class,
            () -> service.issueForWorkspace(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID));

        verifyNoInteractions(workspaceService, grantMapper, auditService);
    }

    @Test
    void revokingAuditsOnlyWhenACodeWasOpen() {
        when(workspaceService.lockAttestationAuthority(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID)).thenReturn(ORG_ID);
        when(grantMapper.revokeOpen(GRANTEE_ID, ORG_ID, GRANTOR_ID)).thenReturn(0, 1);
        when(userMapper.getUserById(GRANTEE_ID)).thenReturn(user(GRANTEE_ID));

        service.revokeForWorkspace(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID);
        verifyNoInteractions(auditService);

        service.revokeForWorkspace(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID);
        verify(auditService).recordStrictScoped(
            "auth.mfa.attestation.revoked", "user", GRANTEE_ID, WORKSPACE_ID, ORG_ID, "Grantee",
            "Revoked a privileged MFA attestation code", Map.of("revokedByUserId", GRANTOR_ID));
    }

    @Test
    void aMalformedCodeIsRefusedWithoutLookingAnythingUp() {
        PrivilegedMfaAttestationService.AttestationRefusal refusal = assertThrows(
            PrivilegedMfaAttestationService.AttestationRefusal.class,
            () -> service.redeem(GRANTEE_ID, SESSION_EPOCH, PASSKEY_ROW_ID, "not a code"));

        assertEquals("malformed_code", refusal.reason());
        verifyNoInteractions(grantMapper, workspaceService, orgMemberService, attestationMapper);
    }

    @Test
    void anUnknownCodeIsRefusedBeforeAnyLock() {
        assertRefused("code_mismatch");

        verifyNoInteractions(workspaceService, orgMemberService, attestationMapper);
    }

    @Test
    void aWorkspaceGrantIsClaimedUnderItsAuthorityLocksAndThenCovered() {
        stubRedeemableWorkspaceGrant();
        when(credentialMapper.findOwnedRowId(PASSKEY_ROW_ID, GRANTEE_ID)).thenReturn(PASSKEY_ROW_ID);
        when(grantMapper.claim(GRANT_ID, GRANTEE_ID, PASSKEY_ROW_ID)).thenReturn(1);
        when(userMapper.getUserById(GRANTEE_ID)).thenReturn(user(GRANTEE_ID));

        assertEquals(ORG_ID, service.redeem(GRANTEE_ID, SESSION_EPOCH, PASSKEY_ROW_ID, CODE).orgId());

        InOrder order = inOrder(workspaceService, userMapper, credentialMapper, grantMapper,
            attestationMapper, auditService);
        order.verify(workspaceService).lockAttestationAuthority(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID);
        order.verify(userMapper).currentSessionEpoch(GRANTEE_ID);
        order.verify(credentialMapper).findOwnedRowId(PASSKEY_ROW_ID, GRANTEE_ID);
        order.verify(grantMapper).claim(GRANT_ID, GRANTEE_ID, PASSKEY_ROW_ID);
        order.verify(attestationMapper).upsertGrantorCoverage(PASSKEY_ROW_ID, ORG_ID, GRANT_ID);
        order.verify(auditService).recordStrictScoped(
            "auth.mfa.attestation.redeemed", "user", GRANTEE_ID, WORKSPACE_ID, ORG_ID, "Grantee",
            "Redeemed a privileged MFA attestation code",
            Map.of("grantId", GRANT_ID, "grantorUserId", GRANTOR_ID, "credentialRowId", PASSKEY_ROW_ID));
    }

    @Test
    void anOrganizationGrantIsRedeemedUnderTheOwnersAuthority() {
        PrivilegedMfaAttestationGrant grant = grant(null);
        when(grantMapper.findByDigest(digest())).thenReturn(grant);
        when(userMapper.currentSessionEpoch(GRANTEE_ID)).thenReturn(SESSION_EPOCH);
        when(credentialMapper.findOwnedRowId(PASSKEY_ROW_ID, GRANTEE_ID)).thenReturn(PASSKEY_ROW_ID);
        when(grantMapper.claim(GRANT_ID, GRANTEE_ID, PASSKEY_ROW_ID)).thenReturn(1);

        service.redeem(GRANTEE_ID, SESSION_EPOCH, PASSKEY_ROW_ID, CODE);

        verify(orgMemberService).lockAttestationAuthority(ORG_ID, GRANTOR_ID, GRANTEE_ID);
        verifyNoInteractions(workspaceService);
    }

    @Test
    void aGrantorWhoLostTheAuthorityIsARefusal() {
        when(grantMapper.findByDigest(digest())).thenReturn(grant(WORKSPACE_ID));
        when(workspaceService.lockAttestationAuthority(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID))
            .thenThrow(new ForbiddenException("Requires the MEMBER_MANAGE permission in this workspace"))
            .thenThrow(new ConflictException("The member's role changed; refresh and try again"))
            .thenReturn(ORG_ID + 1);

        assertRefused("authority_lost");
        assertRefused("authority_lost");
        assertRefused("authority_lost");
        verify(grantMapper, never()).claim(anyLong(), anyInt(), anyInt());
    }

    @Test
    void aGranteeWhoIsNoLongerAMemberIsARefusal() {
        when(grantMapper.findByDigest(digest())).thenReturn(grant(WORKSPACE_ID));
        when(workspaceService.lockAttestationAuthority(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID))
            .thenThrow(new ResourceNotFoundException("User is not a member of this workspace"));

        assertRefused("grantee_not_member");
        verify(grantMapper, never()).claim(anyLong(), anyInt(), anyInt());
    }

    @Test
    void aSessionWhoseEpochMovedIsRefusedBeforeTheGrantIsClaimed() {
        when(grantMapper.findByDigest(digest())).thenReturn(grant(WORKSPACE_ID));
        when(workspaceService.lockAttestationAuthority(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID)).thenReturn(ORG_ID);
        when(userMapper.currentSessionEpoch(GRANTEE_ID)).thenReturn(SESSION_EPOCH + 1);

        assertRefused("session_not_current");
        verify(grantMapper, never()).claim(anyLong(), anyInt(), anyInt());
    }

    @Test
    void aPasskeyNoLongerTheGranteesIsRefusedBeforeTheGrantIsClaimed() {
        stubRedeemableWorkspaceGrant();
        when(credentialMapper.findOwnedRowId(PASSKEY_ROW_ID, GRANTEE_ID)).thenReturn(null);

        assertRefused("credential_not_owned");
        verify(grantMapper, never()).claim(anyLong(), anyInt(), anyInt());
    }

    @Test
    void anUnusableGrantCoversNothing() {
        stubRedeemableWorkspaceGrant();
        when(credentialMapper.findOwnedRowId(PASSKEY_ROW_ID, GRANTEE_ID)).thenReturn(PASSKEY_ROW_ID);

        assertRefused("grant_unusable");
        verify(attestationMapper, never()).upsertGrantorCoverage(anyInt(), anyInt(), anyLong());
        verifyNoInteractions(auditService);
    }

    @Test
    void aBurnLocksTheGranteeBeforeCountingAndCountsNothingForAGoneAccount() {
        when(userMapper.lockById(GRANTEE_ID)).thenReturn(GRANTEE_ID, (Integer) null);

        service.burn(GRANTEE_ID);
        service.burn(GRANTEE_ID);

        InOrder order = inOrder(userMapper, grantMapper);
        order.verify(userMapper).lockById(GRANTEE_ID);
        order.verify(grantMapper).burnOpen(GRANTEE_ID);
        verify(grantMapper).burnOpen(GRANTEE_ID);
    }

    private void assertRefused(String reason) {
        PrivilegedMfaAttestationService.AttestationRefusal refusal = assertThrows(
            PrivilegedMfaAttestationService.AttestationRefusal.class,
            () -> service.redeem(GRANTEE_ID, SESSION_EPOCH, PASSKEY_ROW_ID, CODE));
        assertEquals(reason, refusal.reason());
    }

    private void stubRedeemableWorkspaceGrant() {
        when(grantMapper.findByDigest(digest())).thenReturn(grant(WORKSPACE_ID));
        when(workspaceService.lockAttestationAuthority(WORKSPACE_ID, GRANTOR_ID, GRANTEE_ID)).thenReturn(ORG_ID);
        when(userMapper.currentSessionEpoch(GRANTEE_ID)).thenReturn(SESSION_EPOCH);
    }

    private void stubStoredGrant() {
        doAnswer(invocation -> {
            invocation.getArgument(0, PrivilegedMfaAttestationGrant.class).setId(GRANT_ID);
            return 1;
        }).when(grantMapper).insert(any(PrivilegedMfaAttestationGrant.class));
        PrivilegedMfaAttestationGrant stored = grant(WORKSPACE_ID);
        stored.setExpiresAt("2026-10-08 12:00:00.123456");
        when(grantMapper.findById(GRANT_ID)).thenReturn(stored);
    }

    private static String digest() {
        return MfaAttestationCode.digest(GRANTEE_ID, MfaAttestationCode.normalize(CODE));
    }

    private static PrivilegedMfaAttestationGrant grant(Integer workspaceId) {
        PrivilegedMfaAttestationGrant grant = new PrivilegedMfaAttestationGrant();
        grant.setId(GRANT_ID);
        grant.setOrgId(ORG_ID);
        grant.setWorkspaceId(workspaceId);
        grant.setGrantorUserId(GRANTOR_ID);
        grant.setGranteeUserId(GRANTEE_ID);
        grant.setCodeDigest(digest());
        return grant;
    }

    private static User user(int id) {
        User user = new User();
        user.setId(id);
        user.setDisplayName("Grantee");
        return user;
    }
}
