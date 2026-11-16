package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.PrivilegedMfaAttestationGrant;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MfaAttestationCodeDto;
import ooo.klae.connex.backend.dto.MfaAttestationRedemptionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PrivilegedCredentialAttestationMapper;
import ooo.klae.connex.backend.mappers.PrivilegedMfaAttestationGrantMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.session.StepUpProof;
import ooo.klae.connex.backend.util.MfaAttestationCode;

/**
 * Issues, revokes and redeems grantor-issued attestation codes for privileged MFA (#1534). A
 * grantor who could grant a member's current role issues a code, through a workspace or as an
 * organization owner for an org member; the member redeems it with a passkey assertion and that
 * passkey gains grantor coverage in the grant's organization. Every operation takes the grant's
 * authority locks before touching a grant, so an issue, a revoke, a redemption and a burn of the
 * same grantee serialize. Grantors must vouch in the grant's organization whatever the attestation
 * enforcement switch, so dark-period approvals cannot establish unvetted coverage.
 */
@Service
@RequiredArgsConstructor
public class PrivilegedMfaAttestationService {
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private final PrivilegedMfaAttestationGrantMapper grantMapper;
    private final PrivilegedCredentialAttestationMapper attestationMapper;
    private final WebauthnCredentialMapper credentialMapper;
    private final UserMapper userMapper;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;
    private final PrivilegedAccountService privilegedAccountService;

    /**
     * Issues a code through a workspace, superseding the grantee's open code for its organization.
     *
     * @param workspaceId the workspace the grantor's authority comes from
     * @param grantorId the issuing account, which needs a fresh step-up
     * @param granteeId the member whose passkey the code will attest
     * @return the code, shown once, and its expiry
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MfaAttestationCodeDto issueForWorkspace(int workspaceId, int grantorId, int granteeId) {
        requireReadCommitted();
        requireOtherAccount(grantorId, granteeId);
        sessionSecurityService.requireFreshStepUp(grantorId);
        int orgId = workspaceService.lockAttestationAuthority(workspaceId, grantorId, granteeId);
        requireGrantorVouches(orgId, grantorId);
        return issue(orgId, workspaceId, grantorId, granteeId);
    }

    /**
     * Issues a code as an organization owner for an org member, superseding the member's open code
     * for the organization.
     *
     * @param orgId the organization
     * @param grantorId the issuing owner, which needs a fresh step-up
     * @param granteeId the org member whose passkey the code will attest
     * @return the code, shown once, and its expiry
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MfaAttestationCodeDto issueForOrganization(int orgId, int grantorId, int granteeId) {
        requireReadCommitted();
        requireOtherAccount(grantorId, granteeId);
        sessionSecurityService.requireFreshStepUp(grantorId);
        orgMemberService.lockAttestationAuthority(orgId, grantorId, granteeId);
        requireGrantorVouches(orgId, grantorId);
        return issue(orgId, null, grantorId, granteeId);
    }

    /**
     * Revokes the grantee's open code for the workspace's organization. Coverage already redeemed
     * stays.
     *
     * @param workspaceId the workspace the grantor's authority comes from
     * @param grantorId the revoking account, which needs a fresh step-up
     * @param granteeId the member whose code is revoked
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revokeForWorkspace(int workspaceId, int grantorId, int granteeId) {
        requireReadCommitted();
        requireOtherAccount(grantorId, granteeId);
        sessionSecurityService.requireFreshStepUp(grantorId);
        int orgId = workspaceService.lockAttestationAuthority(workspaceId, grantorId, granteeId);
        requireGrantorVouches(orgId, grantorId);
        revoke(orgId, workspaceId, grantorId, granteeId);
    }

    /**
     * Revokes an org member's open code for the organization. Coverage already redeemed stays.
     *
     * @param orgId the organization
     * @param grantorId the revoking owner, which needs a fresh step-up
     * @param granteeId the org member whose code is revoked
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revokeForOrganization(int orgId, int grantorId, int granteeId) {
        requireReadCommitted();
        requireOtherAccount(grantorId, granteeId);
        sessionSecurityService.requireFreshStepUp(grantorId);
        orgMemberService.lockAttestationAuthority(orgId, grantorId, granteeId);
        requireGrantorVouches(orgId, grantorId);
        revoke(orgId, null, grantorId, granteeId);
    }

    /**
     * Redeems a code for the passkey that signed the redemption's assertion. Called only by
     * {@link PrivilegedMfaAttestationRedemption} after that assertion committed in its own
     * transaction. It takes the grant's authority locks, re-checks under them that the grantor still
     * holds the authority, that the grantee is still a member, that the session's epoch is current
     * and that the passkey is still the grantee's, then claims the grant and records the coverage.
     * Whatever the attestation enforcement switch, it also re-checks that the grantor holds a
     * passkey that vouches in the grant's organization (#1534), so a grantor who lost that coverage
     * after issuing can no longer pass it on.
     *
     * @param granteeId the authenticated account redeeming the code
     * @param expectedSessionEpoch the epoch stamped into the redeeming session
     * @param credentialRowId the passkey that signed the redemption
     * @param rawCode the code as entered
     * @return the organization the passkey now covers
     * @throws AttestationRefusal for every reason the code cannot be used
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MfaAttestationRedemptionDto redeem(
            int granteeId, Integer expectedSessionEpoch, int credentialRowId, String rawCode) {
        requireReadCommitted();
        String code = MfaAttestationCode.normalize(rawCode);
        if (code == null) {
            throw new AttestationRefusal("malformed_code");
        }
        PrivilegedMfaAttestationGrant grant =
            grantMapper.findByDigest(MfaAttestationCode.digest(granteeId, code));
        if (grant == null || grant.getGranteeUserId() != granteeId) {
            throw new AttestationRefusal("code_mismatch");
        }
        lockGrantAuthority(grant);
        if (!privilegedAccountService.hasPasskeyCoveredInOrganization(
                grant.getGrantorUserId(), grant.getOrgId())) {
            throw new AttestationRefusal("grantor_unattested");
        }
        Integer currentEpoch = userMapper.currentSessionEpoch(granteeId);
        if (expectedSessionEpoch == null || !expectedSessionEpoch.equals(currentEpoch)) {
            throw new AttestationRefusal("session_not_current");
        }
        if (credentialMapper.findOwnedRowId(credentialRowId, granteeId) == null) {
            throw new AttestationRefusal("credential_not_owned");
        }
        if (grantMapper.claim(grant.getId(), granteeId, credentialRowId) != 1) {
            throw new AttestationRefusal("grant_unusable");
        }
        attestationMapper.upsertGrantorCoverage(credentialRowId, grant.getOrgId(), grant.getId());
        auditService.recordStrictScoped(
            "auth.mfa.attestation.redeemed",
            "user",
            granteeId,
            grant.getWorkspaceId(),
            grant.getOrgId(),
            displayName(granteeId),
            "Redeemed a privileged MFA attestation code",
            Map.of(
                "grantId", grant.getId(),
                "grantorUserId", grant.getGrantorUserId(),
                "credentialRowId", credentialRowId));
        return new MfaAttestationRedemptionDto(grant.getOrgId());
    }

    /**
     * Counts one refused redemption against every grant of the grantee that is still redeemable.
     * Runs in its own transaction after the refused redemption rolled back, under the grantee's
     * root, so it serializes with issuing and redeeming that grantee's codes.
     *
     * @param granteeId the account whose redemption was refused
     */
    @Transactional
    public void burn(int granteeId) {
        if (userMapper.lockById(granteeId) != null) {
            grantMapper.burnOpen(granteeId);
        }
    }

    /**
     * Re-checks under the authority locks that the grantor's fresh step-up was signed by a passkey
     * the grantor still owns and that vouches in the grant's organization (#1534). Vouching rests on
     * that organization alone, not on the grantor's standing elsewhere. The locks hold the grantor's
     * account row, which every writer of that passkey's ownership and coverage takes first, so the
     * answer stays current until this transaction ends. This applies whatever the attestation
     * enforcement switch.
     */
    private void requireGrantorVouches(int orgId, int grantorId) {
        StepUpProof proof = sessionSecurityService.currentStepUpProof(grantorId);
        if (proof == null || !privilegedAccountService.isPasskeyCoveredInOrganization(
                grantorId, proof.credentialRowId(), orgId)) {
            throw new RecentAuthenticationRequiredException();
        }
    }

    /**
     * Refuses to run unless this is an active {@code READ COMMITTED} transaction (#1534). The
     * re-checks after the authority locks must read committed state, and a method's isolation
     * annotation cannot change a caller's transaction that it joins.
     */
    private static void requireReadCommitted() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || isolation == null
                || isolation != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Passkey approval codes must run in their own READ COMMITTED transaction");
        }
    }

    private void lockGrantAuthority(PrivilegedMfaAttestationGrant grant) {
        try {
            if (grant.getWorkspaceId() == null) {
                orgMemberService.lockAttestationAuthority(
                    grant.getOrgId(), grant.getGrantorUserId(), grant.getGranteeUserId());
                return;
            }
            int orgId = workspaceService.lockAttestationAuthority(
                grant.getWorkspaceId(), grant.getGrantorUserId(), grant.getGranteeUserId());
            if (orgId != grant.getOrgId()) {
                throw new AttestationRefusal("authority_lost");
            }
        } catch (ResourceNotFoundException notMember) {
            throw new AttestationRefusal("grantee_not_member");
        } catch (ForbiddenException | ConflictException | BadRequestException lost) {
            throw new AttestationRefusal("authority_lost");
        }
    }

    private MfaAttestationCodeDto issue(int orgId, Integer workspaceId, int grantorId, int granteeId) {
        int superseded = grantMapper.revokeOpen(granteeId, orgId, grantorId);
        String code = MfaAttestationCode.generate();
        PrivilegedMfaAttestationGrant grant = new PrivilegedMfaAttestationGrant();
        grant.setOrgId(orgId);
        grant.setWorkspaceId(workspaceId);
        grant.setGrantorUserId(grantorId);
        grant.setGranteeUserId(granteeId);
        grant.setCodeDigest(MfaAttestationCode.digest(granteeId, MfaAttestationCode.normalize(code)));
        grantMapper.insert(grant);
        PrivilegedMfaAttestationGrant stored = grantMapper.findById(grant.getId());
        if (stored == null) {
            throw new IllegalStateException("The issued attestation grant was not stored");
        }
        String expiresAt = utcInstant(stored.getExpiresAt());
        auditService.recordStrictScoped(
            "auth.mfa.attestation.issued",
            "user",
            granteeId,
            workspaceId,
            orgId,
            displayName(granteeId),
            "Issued a privileged MFA attestation code",
            Map.of(
                "grantId", grant.getId(),
                "grantorUserId", grantorId,
                "expiresAt", expiresAt,
                "superseded", superseded));
        return new MfaAttestationCodeDto(code, expiresAt);
    }

    private void revoke(int orgId, Integer workspaceId, int grantorId, int granteeId) {
        if (grantMapper.revokeOpen(granteeId, orgId, grantorId) == 0) {
            return;
        }
        auditService.recordStrictScoped(
            "auth.mfa.attestation.revoked",
            "user",
            granteeId,
            workspaceId,
            orgId,
            displayName(granteeId),
            "Revoked a privileged MFA attestation code",
            Map.of("revokedByUserId", grantorId));
    }

    private String displayName(int userId) {
        User user = userMapper.getUserById(userId);
        return user == null ? null : user.getDisplayName();
    }

    private static void requireOtherAccount(int grantorId, int granteeId) {
        if (grantorId == granteeId) {
            throw new ForbiddenException("An attestation code cannot be issued to yourself");
        }
    }

    private static String utcInstant(String databaseTimestamp) {
        return LocalDateTime.parse(databaseTimestamp.replace(' ', 'T'))
            .toInstant(ZoneOffset.UTC)
            .toString();
    }

    /**
     * A redemption refused for a reason the caller must not learn. The orchestrator burns the
     * grantee's open grants, audits the reason and answers with the uniform refusal.
     */
    public static final class AttestationRefusal extends RuntimeException {
        private final String reason;

        AttestationRefusal(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        /** @return the sanitized reason recorded in the denial audit */
        public String reason() {
            return reason;
        }
    }
}
