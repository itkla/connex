package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.PrivilegedMfaAttestationGrant;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MfaAttestationCodeDto;
import ooo.klae.connex.backend.dto.MfaAttestationRedemptionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PrivilegedCredentialAttestationMapper;
import ooo.klae.connex.backend.mappers.PrivilegedMfaAttestationGrantMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.util.MfaAttestationCode;

/**
 * Issues, revokes and redeems grantor-issued attestation codes for privileged MFA (#1534). A
 * grantor who could grant a member's current role issues a code, through a workspace or as an
 * organization owner for an org member; the member redeems it with a passkey assertion and that
 * passkey gains grantor coverage in the grant's organization. Every operation takes the grant's
 * authority locks before touching a grant, so an issue, a revoke, a redemption and a burn of the
 * same grantee serialize. Nothing reads the coverage for authorization yet.
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

    /**
     * Issues a code through a workspace, superseding the grantee's open code for its organization.
     *
     * @param workspaceId the workspace the grantor's authority comes from
     * @param grantorId the issuing account, which needs a fresh step-up
     * @param granteeId the member whose passkey the code will attest
     * @return the code, shown once, and its expiry
     */
    @Transactional
    public MfaAttestationCodeDto issueForWorkspace(int workspaceId, int grantorId, int granteeId) {
        requireOtherAccount(grantorId, granteeId);
        sessionSecurityService.requireRecentAuthentication(grantorId);
        int orgId = workspaceService.lockAttestationAuthority(workspaceId, grantorId, granteeId);
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
    @Transactional
    public MfaAttestationCodeDto issueForOrganization(int orgId, int grantorId, int granteeId) {
        requireOtherAccount(grantorId, granteeId);
        sessionSecurityService.requireRecentAuthentication(grantorId);
        orgMemberService.lockAttestationAuthority(orgId, grantorId, granteeId);
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
    @Transactional
    public void revokeForWorkspace(int workspaceId, int grantorId, int granteeId) {
        requireOtherAccount(grantorId, granteeId);
        sessionSecurityService.requireRecentAuthentication(grantorId);
        int orgId = workspaceService.lockAttestationAuthority(workspaceId, grantorId, granteeId);
        revoke(orgId, workspaceId, grantorId, granteeId);
    }

    /**
     * Revokes an org member's open code for the organization. Coverage already redeemed stays.
     *
     * @param orgId the organization
     * @param grantorId the revoking owner, which needs a fresh step-up
     * @param granteeId the org member whose code is revoked
     */
    @Transactional
    public void revokeForOrganization(int orgId, int grantorId, int granteeId) {
        requireOtherAccount(grantorId, granteeId);
        sessionSecurityService.requireRecentAuthentication(grantorId);
        orgMemberService.lockAttestationAuthority(orgId, grantorId, granteeId);
        revoke(orgId, null, grantorId, granteeId);
    }

    /**
     * Redeems a code for the passkey that signed the redemption's assertion. Called only by
     * {@link PrivilegedMfaAttestationRedemption} after that assertion committed in its own
     * transaction. It takes the grant's authority locks, re-checks under them that the grantor still
     * holds the authority, that the grantee is still a member, that the session's epoch is current
     * and that the passkey is still the grantee's, then claims the grant and records the coverage.
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
