package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.PasskeyApprovalGap;
import ooo.klae.connex.backend.beans.PrivilegedMfaStandingRow;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccount;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccountCounts;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.dto.PasskeyApprovalGapDto;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaConfinementAcknowledgementRequiredException;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Resolves account-wide administrative authority from current control-plane membership state.
 */
@Service
@RequiredArgsConstructor
public class PrivilegedAccountService {
    private static final int STANDING_READ_CHUNK = 500;

    private final UserMapper userMapper;
    private final PrivilegedMfaProperties privilegedMfaProperties;

    /**
     * Returns whether the account currently holds an organization role, a built-in workspace
     * administrator role, or a custom role with administrative permissions.
     *
     * @param userId account to evaluate
     * @return current account-wide privilege state
     */
    public boolean isPrivileged(int userId) {
        return userMapper.isPrivilegedAccount(userId);
    }

    /**
     * The account's privileged-MFA standing as the data stands, whatever the configuration (#1534).
     * Read-only; takes no locks, so a caller deciding under locks must read it after acquiring them.
     *
     * @param userId account to evaluate
     * @return the standing implied by current privilege and passkey state
     */
    public PrivilegedMfaStanding standing(int userId) {
        PrivilegedMfaStandingRow row = userMapper.privilegedMfaStanding(userId);
        if (row == null) {
            throw new IllegalStateException("Privileged MFA standing is unavailable");
        }
        return PrivilegedMfaStanding.of(row);
    }

    /**
     * The standing that is enforced under the current configuration (#1534). Until attestation is
     * enforced, an account whose passkeys are not yet approved stands as satisfied, exactly as a
     * privileged account with any passkey did before.
     *
     * @param userId account to evaluate
     * @return {@link #standing(int)}, with {@link PrivilegedMfaStanding#ATTESTATION_REQUIRED}
     *     relaxed to {@link PrivilegedMfaStanding#SATISFIED} while attestation is not enforced
     */
    public PrivilegedMfaStanding enforcedStanding(int userId) {
        PrivilegedMfaStanding standing = standing(userId);
        if (standing == PrivilegedMfaStanding.ATTESTATION_REQUIRED
                && !privilegedMfaProperties.isAttestationEnforced()) {
            return PrivilegedMfaStanding.SATISFIED;
        }
        return standing;
    }

    /**
     * Whether attestation enforcement is on (#1534), for decision sites that consult it beside
     * standing.
     *
     * @return true when unapproved passkeys no longer satisfy privileged MFA
     */
    public boolean isAttestationEnforced() {
        return privilegedMfaProperties.isAttestationEnforced();
    }

    /**
     * Whether the account's privileged proofs must come from a sufficient passkey (#1534): only
     * while attestation is enforced and the account is privileged. Read-only; a caller deciding
     * under locks must read it after acquiring them.
     *
     * @param userId account to evaluate
     * @return true when a step-up or registration proof must be signed by a sufficient passkey
     */
    public boolean requiresSufficientPasskey(int userId) {
        return privilegedMfaProperties.isAttestationEnforced() && userMapper.isPrivilegedAccount(userId);
    }

    /**
     * The credential ids of the account's sufficient passkeys (#1534), so step-up can offer only
     * those.
     *
     * @param userId account to evaluate
     * @return the raw WebAuthn credential ids, in row order
     */
    public List<byte[]> sufficientPasskeyCredentialIds(int userId) {
        return userMapper.sufficientPasskeyCredentialIds(userId);
    }

    /**
     * Reads what a role edit's confinement check needs from before the write (#1534). Call it after
     * the edit's locks and before its write. While attestation is not enforced it reads nothing, not
     * even the organization, and the later check passes, so the edit behaves as it always has.
     *
     * @param organization supplies the organization the edit grants privilege in
     * @param userIds the accounts the edit can affect
     * @return the baseline to hand to {@link #requireConfinementAcknowledged}
     */
    public ConfinementBaseline confinementBaseline(IntSupplier organization, Collection<Integer> userIds) {
        if (!privilegedMfaProperties.isAttestationEnforced() || userIds.isEmpty()) {
            return ConfinementBaseline.NONE;
        }
        int orgId = organization.getAsInt();
        List<Integer> watched = List.copyOf(new LinkedHashSet<>(userIds));
        return new ConfinementBaseline(orgId, watched, privilegedInOrganization(orgId, watched));
    }

    /**
     * Refuses, after a role edit's write and before its success audit, an unacknowledged edit that
     * makes members newly privileged in the organization while none of their passkeys is approved
     * there (#1534). Only this organization's own facts decide: being already confined by another
     * organization changes nothing, so the answer reveals nothing about other tenants. The refusal
     * rolls the edit back with the rest of its transaction.
     *
     * @param baseline what {@link #confinementBaseline} read before the write
     * @param acknowledged whether the request accepted that members may be confined
     */
    public void requireConfinementAcknowledged(ConfinementBaseline baseline, boolean acknowledged) {
        if (baseline.watchesNothing() || acknowledged) {
            return;
        }
        Set<Integer> newlyPrivileged = privilegedInOrganization(baseline.orgId(), baseline.userIds());
        newlyPrivileged.removeAll(baseline.privilegedBefore());
        if (newlyPrivileged.isEmpty()) {
            return;
        }
        newlyPrivileged.removeAll(coveredInOrganization(baseline.orgId(), newlyPrivileged));
        if (!newlyPrivileged.isEmpty()) {
            throw new PrivilegedMfaConfinementAcknowledgementRequiredException(newlyPrivileged.size());
        }
    }

    /**
     * Whether the passkey lets the account vouch in the organization (#1534): it is the account's
     * own and carries break-glass assurance or is covered there. Vouching in one organization rests
     * on that organization alone, not on the account's standing elsewhere.
     *
     * @param userId account that must own the passkey
     * @param credentialRowId the passkey's {@code webauthn_credential.id}
     * @param orgId the organization the account vouches in
     * @return whether the passkey may back a grant in that organization
     */
    public boolean isPasskeyCoveredInOrganization(int userId, int credentialRowId, int orgId) {
        return userMapper.isPasskeyCoveredInOrganization(userId, credentialRowId, orgId);
    }

    /**
     * Whether the account holds any passkey that vouches in the organization (#1534).
     *
     * @param userId the account
     * @param orgId the organization
     * @return whether one of its passkeys carries break-glass assurance or is covered there
     */
    public boolean hasPasskeyCoveredInOrganization(int userId, int orgId) {
        return userMapper.hasPasskeyCoveredInOrganization(userId, orgId);
    }

    /**
     * Whether the passkey belongs to the account and is sufficient for its privileged MFA: it
     * carries break-glass assurance, or it is approved in every organization where the account is
     * privileged (#1534).
     *
     * @param userId account that must own the passkey
     * @param credentialRowId the passkey's {@code webauthn_credential.id}
     * @return true only for the account's own sufficient passkey
     */
    public boolean isSufficientPasskey(int userId, int credentialRowId) {
        return userMapper.isSufficientPasskey(userId, credentialRowId);
    }

    /**
     * Whether removing the passkey would leave a privileged account without a sufficient one while
     * attestation is enforced (#1534), so that the removal would confine the account. Always false
     * while attestation is not enforced, and for a passkey that is not itself sufficient: removing
     * it changes nothing about the account's standing. Read-only; a caller deciding under locks must
     * read it after acquiring them.
     *
     * @param userId account that owns the passkey
     * @param credentialRowId the passkey's {@code webauthn_credential.id}
     * @return true when the passkey is the account's last sufficient one under enforcement
     */
    public boolean isLastSufficientPasskey(int userId, int credentialRowId) {
        return requiresSufficientPasskey(userId)
                && userMapper.isSufficientPasskey(userId, credentialRowId)
                && !userMapper.hasOtherSufficientPasskey(userId, credentialRowId);
    }

    /**
     * The organizations each of the account's passkeys still needs approval in (#1534). Covers only
     * the account's own data, so it may be shown to the account itself. A passkey with break-glass
     * assurance needs no approval and is not listed.
     *
     * @param userId account to evaluate
     * @return one entry per passkey and missing organization, by passkey then organization
     */
    public List<PasskeyApprovalGapDto> passkeyApprovalGaps(int userId) {
        return userMapper.passkeyApprovalGaps(userId).stream()
                .map(PrivilegedAccountService::toDto)
                .toList();
    }

    /**
     * Counts privileged accounts that hold passkeys but none sufficient (#1534), whatever the
     * configuration, for the startup posture.
     *
     * @return the number of accounts attestation would confine
     */
    public long attestationRequiredAccountCount() {
        return userMapper.countAttestationRequiredAccounts();
    }

    /**
     * Lists accounts that confinement will hold at enrollment: privileged under
     * {@link #isPrivileged(int)} and holding no passkey. Read-only; takes no locks.
     *
     * @param limit maximum number of accounts to return, lowest id first
     * @return the capped sample, carrying only ids and self-service prerequisites
     */
    public List<UnenrolledPrivilegedAccount> unenrolledPrivilegedAccounts(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return userMapper.listUnenrolledPrivilegedAccounts(limit);
    }

    /**
     * Counts the whole population {@link #unenrolledPrivilegedAccounts(int)} samples.
     *
     * @return uncapped counts
     * @throws IllegalStateException when the aggregate yields no row
     */
    public UnenrolledPrivilegedAccountCounts unenrolledPrivilegedAccountCounts() {
        UnenrolledPrivilegedAccountCounts counts = userMapper.countUnenrolledPrivilegedAccounts();
        if (counts == null) {
            throw new IllegalStateException("Unenrolled privileged account inventory is unavailable");
        }
        return counts;
    }

    private static PasskeyApprovalGapDto toDto(PasskeyApprovalGap gap) {
        return new PasskeyApprovalGapDto(
                new Bytes(gap.credentialId()).toBase64UrlString(), gap.orgId(), gap.orgName());
    }

    private Set<Integer> privilegedInOrganization(int orgId, Collection<Integer> userIds) {
        Set<Integer> privileged = new HashSet<>();
        for (List<Integer> chunk : chunks(userIds)) {
            privileged.addAll(userMapper.privilegedInOrganizationAmong(orgId, chunk));
        }
        return privileged;
    }

    private Set<Integer> coveredInOrganization(int orgId, Collection<Integer> userIds) {
        Set<Integer> covered = new HashSet<>();
        for (List<Integer> chunk : chunks(userIds)) {
            covered.addAll(userMapper.coveredInOrganizationAmong(orgId, chunk));
        }
        return covered;
    }

    private static List<List<Integer>> chunks(Collection<Integer> userIds) {
        List<Integer> ordered = List.copyOf(userIds);
        List<List<Integer>> chunks = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += STANDING_READ_CHUNK) {
            chunks.add(ordered.subList(start, Math.min(ordered.size(), start + STANDING_READ_CHUNK)));
        }
        return chunks;
    }
}
