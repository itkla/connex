package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;

/** Fences both scope kinds and verifies strict final residual cleanup before deleting audit parents. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnershipOfferLifecycleIntegrationTest extends AbstractServiceTest {
    @Autowired private OrganizationMapper organizations;
    @Autowired private OrgMemberMapper orgMembers;
    @Autowired private OrgMemberService memberships;
    @Autowired private WorkspaceService workspaces;
    @Autowired private OwnershipOfferMapper ledger;
    @Autowired private OwnershipOfferService offers;
    @Autowired private OwnershipOfferDeliveryService delivery;
    @Autowired private TenantLifecycleControlOperations lifecycle;
    @Autowired private JdbcTemplate jdbc;
    private final List<Integer> accounts = new ArrayList<>();
    private Organization org;
    private User target;
    private String bulkAccountPrefix;
    @Override protected User newUser() { User user = super.newUser(); accounts.add(user.getId()); return user; }
    @BeforeEach void isolatedScope() {
        OwnershipOfferTestFixtures.restartExpiryTraversal(offers);
        org = new Organization(); org.setName("Ownership lifecycle"); org.setSlug("ownership-lifecycle-" + unique()); organizations.insert(org);
        Workspace created = new Workspace(); created.setOrgId(org.getId()); created.setName("Ownership lifecycle"); created.setSlug("ownership-lifecycle-" + unique()); workspaceMapper.insert(created);
        workspace = created; workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");
        orgMembers.addMember(org.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, currentUser);
        target = newUser();
    }
    @AfterEach void clean() {
        jdbc.update("DELETE FROM notification WHERE workspace_id = ?", workspace.getId());
        jdbc.update("DELETE FROM tenant_operation_lease WHERE org_id = ?", org.getId());
        jdbc.update("DELETE FROM tenant_cleanup_tombstone WHERE org_id = ?", org.getId());
        jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        jdbc.update("DELETE FROM organization WHERE id = ?", org.getId());
        if (bulkAccountPrefix != null) {
            jdbc.update("DELETE FROM app_user WHERE username LIKE ?", bulkAccountPrefix + "%");
        }
        accounts.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id)); accounts.clear();
    }
    private long workspaceOffer() { return Long.parseLong(workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), target.getId(), "owner").offer().id()); }

    @Test void organizationFenceClosesOnlyFiftyOffersAndTheScheduledPassDrainsTheRemainder() {
        bulkAccountPrefix = "ownership-fence-" + unique() + "-";
        assertEquals(57, jdbc.update("""
            INSERT INTO app_user (username, display_name, email, email_verified, password_hash, timezone, locale)
            WITH RECURSIVE numbers AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM numbers WHERE n < 57)
            SELECT CONCAT(?, n), CONCAT('Fence offeree ', n), CONCAT(?, n, '@example.invalid'), TRUE, ?, 'UTC', 'en'
            FROM numbers
            """, bulkAccountPrefix, bulkAccountPrefix, currentUser.getPasswordHash()));
        assertEquals(57, jdbc.update("""
            INSERT INTO workspace_member (workspace_id, user_id, role)
            SELECT ?, id, 'member' FROM app_user WHERE username LIKE ?
            """, workspace.getId(), bulkAccountPrefix + "%"));
        assertEquals(57, jdbc.update("""
            INSERT INTO ownership_offer (org_id, workspace_id, scope_kind, offeror_user_id, offeree_user_id,
                membership_id, created_at, expires_at, delivery_state)
            SELECT ?, ?, 'WORKSPACE', ?, wm.user_id, wm.membership_id,
                DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 6 DAY), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY), 'NONE'
            FROM workspace_member wm JOIN app_user u ON u.id = wm.user_id
            WHERE wm.workspace_id = ? AND u.username LIKE ?
            """, org.getId(), workspace.getId(), currentUser.getId(), workspace.getId(), bulkAccountPrefix + "%"));
        assertEquals(50, ledger.fencedOrganizationOffers(org.getId(), 50).size());
        assertEquals(57, ledger.fencedOrganizationOffers(org.getId(), 100).size());

        lifecycle.markOrganizationTearingDown(org.getId(), currentUser.getId());
        assertEquals(50, jdbc.queryForObject(
            "SELECT COUNT(*) FROM ownership_offer WHERE org_id = ? AND state = 'INVALIDATED'", Integer.class, org.getId()));
        assertEquals(50, jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.invalidated'", Integer.class, org.getId()));
        List<OwnershipOffer> pending = ledger.fencedOrganizationOffers(org.getId(), 50);
        assertEquals(7, pending.size());
        assertEquals(7, ledger.expiredCandidates(0, ledger.findExpiryWatermark(), 100).stream()
            .filter(offer -> offer.getOrgId() == org.getId()).count());
        OwnershipOffer remaining = pending.getFirst();
        User offeree = userMapper.getUserById(remaining.getOffereeUserId());
        authenticateAs(offeree, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, offeree);
        assertTrue(offers.listForSelf(offeree.getId()).isEmpty());
        assertThrows(ConflictException.class, () -> offers.accept(remaining.getId(), offeree.getId(), false));
        assertEquals("OPEN", ledger.findById(remaining.getId()).getState());
        assertEquals("member", jdbc.queryForObject(
            "SELECT role FROM workspace_member WHERE workspace_id = ? AND user_id = ?", String.class,
            workspace.getId(), offeree.getId()));

        SecurityContextHolder.clearContext();
        new OwnershipOfferScheduler(offers, delivery).reconcile();
        assertTrue(ledger.fencedOrganizationOffers(org.getId(), 50).isEmpty());
        assertEquals(57, jdbc.queryForObject(
            "SELECT COUNT(*) FROM ownership_offer WHERE org_id = ? AND state = 'INVALIDATED'", Integer.class, org.getId()));
        assertEquals(57, jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE org_id = ? AND action = 'ownership.offer.invalidated'", Integer.class, org.getId()));
        assertEquals("member", jdbc.queryForObject(
            "SELECT role FROM workspace_member WHERE workspace_id = ? AND user_id = ?", String.class,
            workspace.getId(), offeree.getId()));
    }

    @Test void organizationFenceClosesWorkspaceAndOrgOffersWithoutAnOrgToWorkspaceLockPass() {
        User workspaceTarget = newUser();
        long workspaceId = Long.parseLong(workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), workspaceTarget.getId(), "owner").offer().id());
        long organizationId = Long.parseLong(memberships.setMember(org.getId(), currentUser.getId(), target.getId(), "owner").offer().id());
        delivery.deliver(ledger.findById(workspaceId)); delivery.deliver(ledger.findById(organizationId));
        long workspaceVersion = jdbc.queryForObject("SELECT state_version FROM notification_recipient_state WHERE recipient_id = ?", Long.class, workspaceTarget.getId());
        long organizationVersion = jdbc.queryForObject("SELECT state_version FROM notification_recipient_state WHERE recipient_id = ?", Long.class, target.getId());
        lifecycle.markOrganizationTearingDown(org.getId(), currentUser.getId());
        assertEquals("INVALIDATED", ledger.findById(workspaceId).getState());
        assertEquals("INVALIDATED", ledger.findById(organizationId).getState());
        assertTrue(offers.listForSelf(target.getId()).isEmpty());
        assertTrue(offers.listForSelf(workspaceTarget.getId()).isEmpty());
        assertEquals(workspaceVersion + 1, jdbc.queryForObject("SELECT state_version FROM notification_recipient_state WHERE recipient_id = ?", Long.class, workspaceTarget.getId()));
        assertEquals(organizationVersion + 1, jdbc.queryForObject("SELECT state_version FROM notification_recipient_state WHERE recipient_id = ?", Long.class, target.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE workspace_id = ? AND type = 'ownership.offer' AND resolved_at IS NULL", Integer.class, workspace.getId()));
        assertEquals("member", jdbc.queryForObject("SELECT role FROM workspace_member WHERE workspace_id = ? AND user_id = ?", String.class, workspace.getId(), target.getId()));
    }
    @Test void workspaceFenceClearsOrganizationNoticeDestinationsAndTerminalHistory() {
        orgMembers.addMember(org.getId(), target.getId(), "admin");
        long workspaceId = workspaceOffer(); long organizationId = Long.parseLong(memberships.setMember(org.getId(), currentUser.getId(), target.getId(), "owner").offer().id());
        delivery.deliver(ledger.findById(workspaceId)); delivery.deliver(ledger.findById(organizationId));
        lifecycle.acquireWorkspaceTeardown(org.getId(), workspace.getId(), currentUser.getId());
        assertEquals("INVALIDATED", ledger.findById(workspaceId).getState());
        assertEquals("OPEN", ledger.findById(organizationId).getState()); assertNull(ledger.findById(organizationId).getDeliveryWorkspaceId());
        authenticateAs(target, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, target);
        assertEquals("ACCEPTED", offers.accept(organizationId, target.getId(), false).offer().state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE workspace_id = ? AND type = 'ownership.offer'", Integer.class, workspace.getId()));
    }
    @Test void aLateOpenResidualIsStrictlyClosedBeforeItsWorkspaceRootIsDeleted() {
        workspaceOffer(); var acquired = lifecycle.acquireWorkspaceTeardown(org.getId(), workspace.getId(), currentUser.getId());
        OwnershipOffer residual = new OwnershipOffer(); residual.setOrgId(org.getId()); residual.setWorkspaceId(workspace.getId());
        residual.setScopeKind("WORKSPACE"); residual.setOfferorUserId(currentUser.getId()); residual.setOffereeUserId(target.getId());
        residual.setMembershipId(workspaceMapper.getMembershipGenerationId(workspace.getId(), target.getId())); residual.setDeliveryState("NONE"); ledger.insert(residual);
        lifecycle.deleteWorkspaceRoot(org.getId(), workspace.getId(), currentUser.getId(), acquired.lease());
        assertNull(ledger.findById(residual.getId())); assertFalse(ledger.workspaceRootExists(workspace.getId()));
        assertEquals(0, ledger.countWorkspaceReferences(workspace.getId()));
    }
}
