package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationRealtimePublisher;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import tools.jackson.databind.ObjectMapper;

/** Verifies ordinary shared-catalog inbox visibility, after-commit realtime and serialized closure. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnershipOfferNotificationTest extends AbstractServiceTest {
    @Autowired private WorkspaceService workspaces;
    @Autowired private OwnershipOfferService offers;
    @Autowired private OwnershipOfferDeliveryService delivery;
    @Autowired private OwnershipOfferMapper ledger;
    @Autowired private NotificationService inbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private ObjectMapper json;
    @MockitoBean private NotificationRealtimePublisher realtime;
    @MockitoSpyBean private NotificationMapper inboxMapper;
    private final List<Integer> accounts = new ArrayList<>();

    @Override protected User newUser() { User user = super.newUser(); accounts.add(user.getId()); return user; }
    @BeforeEach void freshOwner() {
        OwnershipOfferTestFixtures.restartExpiryTraversal(offers);
        OwnershipOfferTestFixtures.stepUpApprovedCurrent(jdbc, currentUser); clearInvocations(realtime);
    }
    @AfterEach void clean() { reset(inboxMapper); accounts.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id)); accounts.clear(); }

    private long create(User target) { return Long.parseLong(workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), target.getId(), "owner").offer().id()); }
    private Notification delivered(long id, User target) {
        delivery.deliver(ledger.findById(id));
        authenticateAs(target, workspace.getId());
        return notificationMapper.findByDedupe(workspace.getId(), target.getId(), "ownership.offer:" + id);
    }

    @Test
    void deletedOfferAndNoticeAfterDiscoveryDoNotAbortOrdinaryMarkAllRead() {
        OwnershipOfferTestFixtures.stepUpApprovedCurrent(jdbc, currentUser);
        User target = newUser();
        long offerId = Long.parseLong(workspaces.changeMemberRole(
            workspace.getId(), currentUser.getId(), target.getId(), "owner").offer().id());
        delivery.deliver(ledger.findById(offerId));
        authenticateAs(target, workspace.getId());
        Notification offerNotice = inboxMapper.findByDedupe(
            workspace.getId(), target.getId(), "ownership.offer:" + offerId);
        assertNotNull(offerNotice);

        Notification ordinary = new Notification();
        ordinary.setWorkspaceId(workspace.getId());
        ordinary.setRecipientId(target.getId());
        ordinary.setType("workspace.member_added");
        ordinary.setCategory("workspace");
        ordinary.setSeverity("info");
        ordinary.setTemplateVersion(1);
        ordinary.setTitle("Workspace membership changed");
        ordinary.setDedupeKey("ownership-read-all-ordinary:" + target.getId());
        ordinary.setTriggeredAt(inboxMapper.getDatabaseUtcTimestamp());
        inboxMapper.upsert(ordinary);
        assertNull(inboxMapper.findById(target.getId(), ordinary.getId()).getReadAt());

        NotificationMapper realInbox = sql.getMapper(NotificationMapper.class);
        doAnswer(invocation -> {
            List<Notification> candidates = realInbox.findPage(
                target.getId(), "unread", List.of(OwnershipOfferWriter.NOTICE_TYPE),
                null, null, null, null, null, invocation.getArgument(8), 100, 0, 0L,
                invocation.getArgument(12));
            assertEquals(1, candidates.size());
            assertEquals(offerNotice.getId(), candidates.getFirst().getId());
            assertEquals(1, jdbc.update("DELETE FROM notification WHERE id = ?", offerNotice.getId()));
            assertEquals(1, jdbc.update("DELETE FROM ownership_offer WHERE id = ?", offerId));
            return candidates;
        }).when(inboxMapper).findPage(eq(target.getId()), eq("unread"),
            eq(List.of(OwnershipOfferWriter.NOTICE_TYPE)), isNull(), isNull(), isNull(),
            isNull(), isNull(), anyString(), eq(100), eq(0), eq(0L), anyLong());

        assertEquals(0, inbox.markAllRead().getUnread());

        assertNull(ledger.findById(offerId));
        assertNull(inboxMapper.findById(target.getId(), offerNotice.getId()));
        assertNotNull(inboxMapper.findById(target.getId(), ordinary.getId()).getReadAt());
    }

    @Test void inboxPagesCountsFindByIdAndRealtimeUseTheOrdinaryMembershipGate() throws Exception {
        User target = newUser(); long id = create(target); Notification notice = delivered(id, target);
        assertNotNull(notice); assertNull(notice.getSourceType()); assertNull(notice.getSourceId());
        assertNull(notice.getContextType()); assertNull(notice.getContextId());
        assertEquals(Long.toString(id), json.readTree(notice.getData()).path("offerId").asString());
        assertEquals("/settings/personal/workspaces", notice.getActionUrl());
        assertEquals(1, notificationMapper.findPage(target.getId(), "active", null, null, null, 100, 0).size());
        assertEquals(1, notificationMapper.getUnreadCounts(target.getId()).getUnread());
        assertNotNull(notificationMapper.findById(target.getId(), notice.getId()));
        assertNull(notificationMapper.findById(currentUser.getId(), notice.getId()));
        verify(realtime).send(eq(target.getId()), argThat(frame -> frame.notification() != null && frame.notification().getId() == notice.getId()));
        jdbc.update("UPDATE workspace_member SET status = 'pending' WHERE workspace_id = ? AND user_id = ?", workspace.getId(), target.getId());
        assertNull(notificationMapper.findById(target.getId(), notice.getId()));
        assertEquals(0, notificationMapper.getUnreadCounts(target.getId()).getUnread());
    }

    @Test void declineResolvesHistoryAndDelayedDeliveryAndRestoreCannotReviveIt() {
        User target = newUser(); long id = create(target); var candidate = ledger.findById(id); Notification notice = delivered(id, target);
        offers.decline(id, target.getId()); delivery.deliver(candidate);
        assertNotNull(notificationMapper.findById(target.getId(), notice.getId()).getResolvedAt());
        assertEquals(0, notificationMapper.getUnreadCounts(target.getId()).getUnread());
        assertTrue(notificationMapper.findPage(target.getId(), "active", null, null, null, 100, 0).isEmpty());
        assertThrows(ooo.klae.connex.backend.exceptions.ConflictException.class, () -> inbox.restore(notice.getId()));
        assertNotNull(notificationMapper.findById(target.getId(), notice.getId()).getResolvedAt());
    }

    @Test void acceptRevokeExpiryAndInvalidationEachResolveTheDeliveredNotice() {
        for (String action : List.of("accept", "revoke", "expiry", "invalidate")) {
            authenticateAs(currentUser, workspace.getId()); OwnershipOfferTestFixtures.stepUpApprovedCurrent(jdbc, currentUser);
            User target = newUser(); long id = create(target); var candidate = ledger.findById(id); Notification notice = delivered(id, target);
            switch (action) {
                case "accept" -> { OwnershipOfferTestFixtures.stepUpApprovedCurrent(jdbc, target); offers.accept(id, target.getId(), false); }
                case "revoke" -> { authenticateAs(currentUser, workspace.getId()); OwnershipOfferTestFixtures.stepUpApprovedCurrent(jdbc, currentUser); offers.revoke("WORKSPACE", workspace.getId(), id, currentUser.getId()); }
                case "expiry" -> { jdbc.update("UPDATE ownership_offer SET created_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 8 DAY), expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY) WHERE id = ?", id); offers.expire(); }
                default -> { authenticateAs(currentUser, workspace.getId()); OwnershipOfferTestFixtures.stepUpApprovedCurrent(jdbc, currentUser); workspaces.removeMember(workspace.getId(), currentUser.getId(), target.getId()); }
            }
            delivery.deliver(candidate); authenticateAs(target, workspace.getId());
            assertEquals(0, notificationMapper.getUnreadCounts(target.getId()).getUnread());
            if (!action.equals("invalidate")) { assertNotNull(notificationMapper.findById(target.getId(), notice.getId()).getResolvedAt()); }
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE recipient_id = ? AND dedupe_key = ? AND resolved_at IS NULL", Integer.class, target.getId(), "ownership.offer:" + id));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void deliveryFencesEachPartyBeforeCreatingAnyNotice(boolean offeror) {
        User target = newUser(); long id = create(target);
        jdbc.update("UPDATE app_user SET account_deletion_reserved_at = UTC_TIMESTAMP(6), account_deletion_reservation_owner = 'ownership-delivery-test', account_deletion_reservation_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id = ?", offeror ? currentUser.getId() : target.getId());
        assertThrows(ooo.klae.connex.backend.exceptions.ResourceNotFoundException.class, () -> delivery.deliver(ledger.findById(id)));
        assertEquals("PENDING", ledger.findById(id).getDeliveryState());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedupe_key = ?", Integer.class, "ownership.offer:" + id));
    }

    @Test void largeOfferIdsStayStringsInCardsAndNoticeData() throws Exception {
        User target = newUser(); long original = create(target); long large = 9_007_199_254_740_993L;
        jdbc.update("UPDATE ownership_offer SET id = ? WHERE id = ?", large, original);
        Notification notice = delivered(large, target);
        var data = json.readTree(notice.getData()); assertTrue(data.path("offerId").isString());
        assertEquals(Long.toString(large), data.path("offerId").asString());
        assertEquals(Long.toString(large), offers.listForSelf(target.getId()).getFirst().id());
        offers.decline(large, target.getId());
        assertNotNull(notificationMapper.findById(target.getId(), notice.getId()).getResolvedAt());
    }

    @Test void missingDeliveredRecipientStateRollsBackClosureAndResolution() {
        User target = newUser(); long id = create(target); Notification notice = delivered(id, target);
        jdbc.update("DELETE FROM notification_recipient_state WHERE recipient_id = ?", target.getId());
        assertThrows(IllegalStateException.class, () -> offers.decline(id, target.getId()));
        assertEquals("OPEN", ledger.findById(id).getState());
        assertNull(notificationMapper.findById(target.getId(), notice.getId()).getResolvedAt());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_recipient_state WHERE recipient_id = ?", Integer.class, target.getId()));
    }

    @Test void deletionReservationAndGenerationFencePreventPersistence() {
        User target = newUser(); long id = create(target);
        jdbc.update("UPDATE app_user SET account_deletion_reserved_at = UTC_TIMESTAMP(6), account_deletion_reservation_owner = 'ownership-test-reservation', account_deletion_reservation_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id = ?", target.getId());
        assertThrows(ooo.klae.connex.backend.exceptions.ResourceNotFoundException.class, () -> delivery.deliver(ledger.findById(id)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE recipient_id = ? AND type = 'ownership.offer'", Integer.class, target.getId()));
        jdbc.update("UPDATE app_user SET account_deletion_reserved_at = NULL, account_deletion_reservation_owner = NULL, account_deletion_reservation_until = NULL WHERE id = ?", target.getId());
        workspaceMapper.removeMember(workspace.getId(), target.getId()); workspaceMapper.addMember(workspace.getId(), target.getId(), "member");
        delivery.deliver(ledger.findById(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE recipient_id = ? AND type = 'ownership.offer'", Integer.class, target.getId()));
    }
}
