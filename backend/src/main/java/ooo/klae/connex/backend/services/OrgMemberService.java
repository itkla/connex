package ooo.klae.connex.backend.services;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntConsumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.OrgMemberDto;
import ooo.klae.connex.backend.dto.MemberRoleChange;
import ooo.klae.connex.backend.dto.OrgMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.OrganizationOwnerRequiredException;
import ooo.klae.connex.backend.exceptions.OrganizationMembershipRequiredException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PrivilegedCredentialAttestationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Organization membership and org-level authorization (#316). Org members hold
 * org-wide authority — {@code admin} administers org-scoped configuration (SSO
 * today), {@code owner} additionally manages the org's members. This is distinct
 * from workspace membership: owning or administering a workspace grants no org
 * authority, which is what stops a workspace admin from reaching org SSO config.
 */
@Service
@RequiredArgsConstructor
public class OrgMemberService {

    /** Organization roles in ascending privilege order. */
    public enum OrgRole {
        ADMIN, OWNER;

        static OrgRole of(String value) {
            return value == null ? null : OrgRole.valueOf(value.trim().toUpperCase());
        }
    }

    private final OrgMemberMapper orgMemberMapper;
    private final PrivilegedCredentialAttestationMapper attestationMapper;
    private final OrganizationMapper organizationMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;
    private final RegistrationVerificationService registrationVerificationService;
    private final PrivilegedAccountService privilegedAccountService;
    private final OwnershipOfferWriter ownershipOfferWriter;

    /**
     * Records a user as the founding owner of a freshly created organization and covers the
     * owner's existing passkeys in it (#1534). Callers hold the owner's {@code app_user} row
     * {@code FOR SHARE}, which passkey registration takes exclusively, so a passkey registered
     * concurrently is either covered here or picks up the founder coverage when it commits.
     *
     * @param orgId the organization just created
     * @param userId the founding owner
     * @return the number of the owner's passkeys now covered in the organization
     */
    public int addFoundingOwner(int orgId, int userId) {
        if (organizationMapper.lockById(orgId) == null || !orgMemberMapper.getMembers(orgId).isEmpty()
                || orgMemberMapper.hasOrganizationWorkspace(orgId)) {
            throw new ConflictException("Founding ownership requires a newly provisioned organization");
        }
        orgMemberMapper.addFoundingMember(orgId, userId);
        return attestationMapper.insertFoundingCoverage(orgId, userId);
    }

    /**
     * Refuses when the user is the only owner of any organization — deleting the account would
     * leave that org ownerless (org_member is {@code ON DELETE CASCADE}, bypassing the last-owner
     * guard on the org-member API). The user, sorted organization roots, and owner rows are locked
     * in that order so concurrent co-owner deletions and org mutations serialize. Must run in a
     * transaction. They must transfer ownership first.
     */
    public void assertNotSoleOwnerOfAnyOrg(int userId) {
        assertNotSoleOwnerOfAnyOrg(userId, List.of());
    }

    /**
     * Locks the ascending union of owned organization roots exclusively and referenced roots for
     * share before checking the owned organizations' final owner rows.
     */
    public void assertNotSoleOwnerOfAnyOrg(
            int userId, Collection<Integer> sharedOrgIds) {
        assertAccountOrganizationRecovery(userId, sharedOrgIds, null);
    }

    List<Integer> discoverAccountMembershipOrgIds(int userId) {
        return orgMemberMapper.discoverAccountMembershipOrgIds(userId).stream().distinct().sorted().toList();
    }

    /** Retains all organization memberships before credentials, audit heads and account cascade. */
    void assertAccountDeletionOrganizationRecovery(
            int userId, Collection<Integer> sharedOrgIds, List<Integer> membershipOrgIds) {
        assertAccountOrganizationRecovery(userId, sharedOrgIds, membershipOrgIds);
    }

    private void assertAccountOrganizationRecovery(
            int userId, Collection<Integer> sharedOrgIds, List<Integer> membershipOrgIds) {
        if (userMapper.lockById(userId) == null) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
        List<Integer> ownedOrgIds = orgMemberMapper.orgIdsOwnedBy(userId).stream()
                .distinct()
                .sorted()
                .toList();
        Set<Integer> owned = new HashSet<>(ownedOrgIds);
        TreeSet<Integer> ordered = new TreeSet<>(ownedOrgIds);
        ordered.addAll(sharedOrgIds);
        if (membershipOrgIds != null) {
            ordered.addAll(membershipOrgIds);
        }
        for (int orgId : ordered) {
            if (owned.contains(orgId)) {
                organizationMapper.lockById(orgId);
            } else {
                organizationMapper.lockByIdForShare(orgId);
            }
        }
        for (int orgId : ownedOrgIds) {
            if (orgMemberMapper.lockOwnerIds(orgId).size() <= 1) {
                throw new BadRequestException("Transfer organization ownership before deleting your account");
            }
        }
        if (membershipOrgIds != null) {
            for (int orgId : new TreeSet<>(membershipOrgIds)) {
                if (!owned.contains(orgId)) {
                    orgMemberMapper.lockExactRole(orgId, userId);
                }
            }
            if (!new HashSet<>(membershipOrgIds).containsAll(orgMemberMapper.lockAccountMembershipOrgIds(userId))) {
                throw new ConflictException("Account organization memberships changed; retry account deletion");
            }
        }
    }

    /** Requires the user to hold org {@code admin} or {@code owner} in the organization. */
    public void requireOrgAdmin(int orgId, int userId) {
        if (OrgRole.of(orgMemberMapper.getRole(orgId, userId)) == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
    }

    /** Requires and locks a current organization administrator membership inside a transaction. */
    public void requireOrgAdminForUpdate(int orgId, int userId) {
        if (OrgRole.of(orgMemberMapper.getRoleForUpdate(orgId, userId)) == null) {
            throw new ForbiddenException("Requires an organization administrator role");
        }
    }

    /** Requires the user to hold org {@code owner} in the organization. */
    public void requireOrgOwner(int orgId, int userId) {
        if (!isOrgOwner(orgId, userId)) {
            throw new ForbiddenException("Requires the organization owner role");
        }
    }

    /** Shared owner authority for issuance and read-only badge visibility. */
    private boolean isOrgOwner(int orgId, int userId) {
        return OrgRole.of(orgMemberMapper.getRole(orgId, userId)) == OrgRole.OWNER;
    }

    /** The organizations the user administers, with their org role. */
    public List<OrgMembershipDto> membershipsForUser(int userId) {
        return orgMemberMapper.getMembershipsForUser(userId);
    }

    /** The user's org role in the organization, or null when they hold none. */
    public String orgRoleOf(int orgId, int userId) {
        return orgMemberMapper.getRole(orgId, userId);
    }

    /** The members of an organization. Requires the actor to be an org administrator. */
    public List<OrgMemberDto> listMembers(int orgId, int actorId) {
        requireOrgAdmin(orgId, actorId);
        List<OrgMemberDto> members = orgMemberMapper.getMembers(orgId);
        if (!isOrgOwner(orgId, actorId)
                || organizationMapper.getActiveById(orgId) == null || members.isEmpty()) {
            return members;
        }
        List<Integer> ids = members.stream().map(OrgMemberDto::getId).toList();
        Set<Integer> reserved = Set.copyOf(userMapper.findAccountDeletionReservedIds(ids));
        if (reserved.contains(actorId)
                || !privilegedAccountService.hasPasskeyCoveredInOrganization(actorId, orgId)) {
            return members;
        }
        Set<Integer> needed = privilegedAccountService.passkeyApprovalNeeded(orgId, ids);
        for (OrgMemberDto member : members) {
            if (member.getId() != actorId && !reserved.contains(member.getId())) {
                member.setPasskeyApprovalNeeded(needed.contains(member.getId()));
            }
        }
        return members;
    }

    /**
     * Adds an org administrator by email, or changes their role if already a member. Owner-gated;
     * the email must belong to an existing Connex account that still holds it. Mailbox proof and
     * the tenancy rule for a new grant are enforced by the delegated {@link #setMember}, which
     * every org-membership write goes through. An unknown address answers exactly like an account
     * that is not a tenant, so the endpoint does not reveal which addresses have accounts (#1534).
     * Transactional so that delegated call — reached by an internal call that bypasses the
     * transactional proxy — still runs under this boundary, keeping its last-owner row lock and
     * audit write atomic, at the {@code READ COMMITTED} isolation its tenancy read relies on.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberRoleChange<OrgMemberDto> setMemberByEmail(int orgId, int actorId, String emailRaw, String roleRaw) {
        return setMemberByEmail(orgId, actorId, emailRaw, roleRaw, false);
    }

    /**
     * Adds an org administrator by email, or changes their role, as
     * {@link #setMemberByEmail(int, int, String, String)} does. Once attestation is enforced, an
     * add or role change that makes the account newly privileged here without a passkey approved
     * here is refused unless acknowledged (#1534).
     *
     * @param acknowledgeConfinement whether the actor accepts that the account may be confined
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberRoleChange<OrgMemberDto> setMemberByEmail(
            int orgId, int actorId, String emailRaw, String roleRaw, boolean acknowledgeConfinement) {
        return setMemberByEmail(orgId, actorId, emailRaw, roleRaw, acknowledgeConfinement, targetId -> {});
    }

    /**
     * Captures the eligible account resolved by email for refusal attribution before taking roots.
     * The callback only captures the local target id; it must not acquire locks or append audits.
     * Failures before resolution do not invoke it. Authorization and mailbox rechecks remain under
     * this READ COMMITTED transaction, as in the ordinary by-email entry point.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberRoleChange<OrgMemberDto> setMemberByEmail(
            int orgId, int actorId, String emailRaw, String roleRaw, boolean acknowledgeConfinement,
            IntConsumer resolvedTarget) {
        Objects.requireNonNull(resolvedTarget, "resolvedTarget");
        if (roleRaw != null && "owner".equalsIgnoreCase(roleRaw.trim())) {
            OwnershipOfferWriter.requireReadCommitted();
        }
        requireOrgOwner(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        OrgRole role = parseRole(roleRaw);
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        Integer targetId = orgMemberMapper.findEligibleUserIdByEmail(orgId, email);
        if (targetId == null) {
            throw notAnActiveTenant();
        }
        resolvedTarget.accept(targetId);
        lockMembershipUserRoots(actorId, targetId);
        User fresh = userMapper.getUserByIdForShare(targetId);
        if (fresh == null || !email.equalsIgnoreCase(fresh.getEmail())) {
            throw new ConflictException("The account's email changed; refresh and try again");
        }
        return applyMember(orgId, actorId, targetId, role, acknowledgeConfinement);
    }

    /**
     * Adds a user to the organization or changes their org role. Requires the actor
     * to be an org owner; the target must be a real user; and the organization must
     * always keep at least one owner (a sole owner cannot be demoted to admin). The
     * sorted actor/target users, the organization, and owner rows are locked in
     * that order so role changes serialize with organization mutations, audit
     * attribution, and account deletion.
     *
     * <p>This is the shared entry point for both the by-id and by-email endpoints, so the
     * mailbox-proof check lives here: while the instance runs registration
     * verification, an account that has not proven its address cannot be <em>granted</em> org-wide
     * authority, whichever endpoint addressed it. The check is scoped to that grant — an account
     * that already holds an {@code org_member} row keeps being manageable, so a legacy unverified
     * member can still be re-roled or removed rather than being frozen at its current authority.
     *
     * <p>A role set on behalf of another account ends that member's founder attribution, so the
     * passkeys it enrolls afterwards are no longer covered as the founder's (#1534). An owner
     * changing their own role keeps it.
     *
     * <p>A new membership is granted only to an existing tenant: an active member of an active
     * workspace in the organization (#1534). Otherwise any owner could confer organization-wide
     * privilege, and with it privileged-MFA confinement, on any account at all. Every way such an
     * add can fail before the account is known to be a tenant answers with the same refusal, so it
     * does not reveal whether an account exists. The tenancy read is a plain read under the target's
     * shared account row at {@code READ COMMITTED}: every writer that removes or deactivates a
     * workspace membership takes that row exclusively, so it has committed already or waits. The
     * organization is locked only while active, so nothing joins one whose teardown has begun.
     *
     * <p>Before any lock, one organization-scoped read asks whether the target may be addressed at
     * all: an existing member or an existing tenant. An unknown account and an outsider answer that
     * read alike and are refused before taking any lock, so neither the refusal nor its cost tells
     * them apart. The answer is revalidated under the locks.
     *
     * <p>Promotion of a non-owner creates consent and preserves the current membership, including
     * its absence. The offeree must accept before ownership is granted; existing-owner repair and
     * other role changes retain their direct behavior.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberRoleChange<OrgMemberDto> setMember(int orgId, int actorId, int targetUserId, String roleRaw) {
        return setMember(orgId, actorId, targetUserId, roleRaw, false);
    }

    /**
     * Adds a user to the organization or changes their org role, as
     * {@link #setMember(int, int, int, String)} does. Once attestation is enforced, an add or role
     * change that makes the account newly privileged here without a passkey approved here is
     * refused unless acknowledged (#1534).
     *
     * @param acknowledgeConfinement whether the actor accepts confinement from a direct role change;
     * ownership offers require the offeree's acknowledgement at acceptance instead
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberRoleChange<OrgMemberDto> setMember(
            int orgId, int actorId, int targetUserId, String roleRaw, boolean acknowledgeConfinement) {
        if (roleRaw != null && "owner".equalsIgnoreCase(roleRaw.trim())) {
            OwnershipOfferWriter.requireReadCommitted();
        }
        requireOrgOwner(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        OrgRole role = parseRole(roleRaw);
        if (!orgMemberMapper.isEligibleForMembership(orgId, targetUserId)) {
            throw notAnActiveTenant();
        }
        return applyMember(orgId, actorId, targetUserId, role, acknowledgeConfinement);
    }

    private MemberRoleChange<OrgMemberDto> applyMember(int orgId, int actorId, int targetUserId, OrgRole role, boolean acknowledgeConfinement) {
        try {
            lockMembershipUserRoots(actorId, targetUserId);
        } catch (ResourceNotFoundException unknownTarget) {
            throw notAnActiveTenant();
        }
        if (role == OrgRole.OWNER) {
            ownershipOfferWriter.requireUnreserved(actorId);
            ownershipOfferWriter.requireUnreserved(targetUserId);
            ownershipOfferWriter.requireActorAuthentication(actorId);
        }
        if (organizationMapper.lockActiveById(orgId) == null) {
            throw new ForbiddenException("Requires the organization owner role");
        }
        User target = userMapper.getUserByIdForShare(targetUserId);
        if (target == null) {
            throw notAnActiveTenant();
        }
        List<Integer> ownerIds = lockCurrentOwnerIds(orgId, actorId);
        String currentRole = role == OrgRole.OWNER ? orgMemberMapper.lockExactRole(orgId, targetUserId)
            : orgMemberMapper.getRoleForUpdate(orgId, targetUserId);
        if (currentRole == null) {
            if (!orgMemberMapper.isActiveWorkspaceTenant(orgId, targetUserId)) {
                throw notAnActiveTenant();
            }
            if (registrationVerificationService.requiresMailboxProof(target)) {
                throw new BadRequestException("The account must verify its email before joining the organization");
            }
        }
        if (role != OrgRole.OWNER && isSoleOwner(ownerIds, targetUserId)) {
            throw new OrganizationOwnerRequiredException("An organization must keep at least one owner");
        }
        if (role == OrgRole.OWNER && !"owner".equals(currentRole)) {
            return new MemberRoleChange<>(currentMember(orgId, targetUserId),
                ownershipOfferWriter.createLocked(orgId, null, actorId, targetUserId));
        }
        ConfinementBaseline baseline =
                privilegedAccountService.confinementBaseline(() -> orgId, List.of(targetUserId));
        if (actorId == targetUserId) {
            orgMemberMapper.addMember(orgId, targetUserId, role.name().toLowerCase());
        } else {
            orgMemberMapper.addMemberClearingFounder(orgId, targetUserId, role.name().toLowerCase());
        }
        privilegedAccountService.requireConfinementAcknowledged(baseline, acknowledgeConfinement);
        ownershipOfferWriter.invalidateScopeLocked(orgId, null);
        auditService.record("org.member.set", "organization", orgId, target.getDisplayName(),
                "Set " + target.getDisplayName() + " to org " + role.name().toLowerCase(), null);
        return MemberRoleChange.direct(currentMember(orgId, targetUserId));
    }

    /**
     * Removes a user's org membership. Requires the actor to be an org owner; the
     * organization must always keep at least one owner. Sorted actor/target users
     * are locked before the organization and owner rows.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void removeMember(int orgId, int actorId, int targetUserId) {
        requireOrgOwner(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        lockMembershipUserRoots(actorId, targetUserId);
        if (organizationMapper.lockById(orgId) == null) {
            throw new ForbiddenException("Requires the organization owner role");
        }
        List<Integer> ownerIds = lockCurrentOwnerIds(orgId, actorId);
        if (isSoleOwner(ownerIds, targetUserId)) {
            throw new OrganizationOwnerRequiredException("An organization must keep at least one owner");
        }
        if (orgMemberMapper.removeMember(orgId, targetUserId) == 0) {
            throw new ResourceNotFoundException("User is not an organization member");
        }
        ownershipOfferWriter.invalidatePartyLocked(orgId, null, targetUserId);
        auditService.record("org.member.remove", "organization", orgId, null,
                "Removed org member " + targetUserId, null);
    }

    /**
     * Removes the caller's own organization membership: the safe exit for an account that
     * membership confines under privileged MFA (#1534). Self-only and without step-up, since
     * leaving only gives up authority and a confined account cannot produce a sufficient step-up.
     * The last owner cannot leave. Locks in {@link #setMember}'s order: the caller's account row
     * shared, the organization exclusively and only while active, the owner rows, then the
     * caller's own row. The audit is strict, so an exit that cannot be recorded does not happen.
     *
     * @param orgId the organization to leave
     * @param userId the departing member
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void leave(int orgId, int userId) {
        if (userMapper.lockByIdForShare(userId) == null || organizationMapper.lockActiveById(orgId) == null) {
            throw notAnOrganizationMember();
        }
        List<Integer> ownerIds = orgMemberMapper.lockOwnerIds(orgId);
        if (orgMemberMapper.getRoleForUpdate(orgId, userId) == null) {
            throw notAnOrganizationMember();
        }
        if (isSoleOwner(ownerIds, userId)) {
            throw new OrganizationOwnerRequiredException("An organization must keep at least one owner");
        }
        orgMemberMapper.removeMember(orgId, userId);
        ownershipOfferWriter.invalidatePartyLocked(orgId, null, userId);
        auditService.recordStrictScoped("org.member.leave", "organization", orgId, null, orgId,
                null, "Left the organization", null);
    }

    /**
     * Locks an organization owner's authority to attest an org member's passkeys for privileged
     * MFA (#1534), in {@link #setMember}'s order: both accounts shared and ascending, the
     * organization exclusive and only while it is active, the owner rows, then exactly the
     * member's own row. Both accounts' deletion reservations are read under their locks, so a
     * reservation or teardown that commits while this waits is never missed.
     *
     * @param orgId the organization
     * @param grantorId the owner issuing, revoking or standing behind a redeemed grant
     * @param granteeId the org member whose passkeys are attested
     */
    void lockAttestationAuthority(int orgId, int grantorId, int granteeId) {
        requireOrgOwner(orgId, grantorId);
        lockMembershipUserRoots(grantorId, granteeId);
        if (organizationMapper.lockActiveById(orgId) == null
                || !Boolean.FALSE.equals(userMapper.isAccountDeletionReservedForShare(grantorId))) {
            throw new ForbiddenException("Requires the organization owner role");
        }
        lockCurrentOwnerIds(orgId, grantorId);
        if (!Boolean.FALSE.equals(userMapper.isAccountDeletionReservedForShare(granteeId))
                || orgMemberMapper.lockExactRole(orgId, granteeId) == null) {
            throw new ResourceNotFoundException("User is not a member of this organization");
        }
    }

    /**
     * Locks the organization's owner rows and verifies the actor's current owner
     * authority. The lock makes authorization, the last-owner check, and the
     * subsequent mutation one serialized step inside the surrounding transaction.
     */
    /** Reads the current organization member without fabricating an interim membership. */
    OrgMemberDto currentMember(int orgId, int userId) {
        return orgMemberMapper.getMembers(orgId).stream().filter(member -> member.getId() == userId)
            .findFirst().orElse(null);
    }

    /** Locks consent's account prefix in ascending order with shared organization account roots. */
    void lockOfferAccounts(List<Integer> users, boolean checkReservations) {
        for (int userId : new TreeSet<>(users)) {
            if (userMapper.lockByIdForShare(userId) == null) {
                throw new ResourceNotFoundException("Ownership offer not found");
            }
            if (checkReservations) {
                ownershipOfferWriter.requireUnreserved(userId);
            }
        }
    }

    /** Locks the organization, owner rows and exact offeree row in the established hierarchy. */
    void lockOwnershipOfferScope(int orgId, int offereeId) {
        if (organizationMapper.lockById(orgId) == null) {
            throw new ResourceNotFoundException("Ownership offer not found");
        }
        orgMemberMapper.lockOwnerIds(orgId);
        orgMemberMapper.lockExactRole(orgId, offereeId);
    }

    private List<Integer> lockCurrentOwnerIds(int orgId, int actorId) {
        List<Integer> ownerIds = orgMemberMapper.lockOwnerIds(orgId);
        if (!ownerIds.contains(actorId)) {
            throw new ForbiddenException("Requires the organization owner role");
        }
        return ownerIds;
    }

    private void lockMembershipUserRoots(int actorId, int targetUserId) {
        int firstUserId = Math.min(actorId, targetUserId);
        int secondUserId = Math.max(actorId, targetUserId);
        lockMembershipUserRoot(firstUserId, actorId);
        if (secondUserId != firstUserId) {
            lockMembershipUserRoot(secondUserId, actorId);
        }
    }

    private void lockMembershipUserRoot(int userId, int actorId) {
        if (userMapper.lockByIdForShare(userId) != null) {
            return;
        }
        if (userId == actorId) {
            throw new ForbiddenException("Requires the organization owner role");
        }
        throw new ResourceNotFoundException("User not found: " + userId);
    }

    private static BadRequestException notAnActiveTenant() {
        return new BadRequestException(
                "Only active members of a workspace in this organization can be added to it");
    }

    private static ResourceNotFoundException notAnOrganizationMember() {
        return new OrganizationMembershipRequiredException("You are not a member of this organization");
    }

    private boolean isSoleOwner(List<Integer> ownerIds, int userId) {
        return ownerIds.size() <= 1 && ownerIds.contains(userId);
    }

    private OrgRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Org role is required");
        }
        try {
            return OrgRole.of(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown org role: " + raw);
        }
    }
}
