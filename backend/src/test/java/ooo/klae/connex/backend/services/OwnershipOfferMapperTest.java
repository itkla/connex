package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;

/** Exercises V229 constraints and database-clock conditional claims against real MySQL. */
@Transactional(isolation = Isolation.READ_COMMITTED)
class OwnershipOfferMapperTest extends AbstractServiceTest {
    @Autowired private OwnershipOfferMapper offers;
    @Autowired private JdbcTemplate jdbc;

    private OwnershipOffer insert(User target) {
        OwnershipOffer offer = new OwnershipOffer();
        offer.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        offer.setWorkspaceId(workspace.getId());
        offer.setScopeKind("WORKSPACE");
        offer.setDeliveryState("PENDING");
        offer.setOfferorUserId(currentUser.getId());
        offer.setOffereeUserId(target.getId());
        offer.setMembershipId(workspaceMapper.getMembershipGenerationId(workspace.getId(), target.getId()));
        offers.insert(offer);
        return offers.findById(offer.getId());
    }

    @Test void uniqueOpenSlotRefusesDuplicateAndAllowsTerminalReplacement() {
        User target = newUser();
        OwnershipOffer first = insert(target);
        assertThrows(DataIntegrityViolationException.class, () -> insert(target));
        assertEquals(1, offers.close(first.getId(), "DECLINED", target.getId(), "declined"));
        assertNotEquals(first.getId(), insert(target).getId());
    }

    @Test void claimRequiresTheOffereeAndAnUnexpiredOpenOffer() {
        OwnershipOffer offer = insert(newUser());
        assertEquals(0, offers.accept(offer.getId(), currentUser.getId()));
        assertEquals(1, offers.accept(offer.getId(), offer.getOffereeUserId()));
        assertEquals(0, offers.accept(offer.getId(), offer.getOffereeUserId()));
        assertEquals("ACCEPTED", offers.findById(offer.getId()).getState());
    }

    @Test void expiredConsentCannotBeClaimedOrListed() {
        OwnershipOffer offer = insert(newUser());
        jdbc.update("UPDATE ownership_offer SET created_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 8 DAY), expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY) WHERE id = ?", offer.getId());
        assertTrue(offers.isExpired(offer.getId()));
        assertEquals(0, offers.accept(offer.getId(), offer.getOffereeUserId()));
        assertTrue(offers.listForOfferee(offer.getOffereeUserId()).isEmpty());
    }

    @Test void expiryPagesUseAscendingIdsAndBothTraversalBoundsRatherThanExpiryOrder() {
        OwnershipOffer first = insert(newUser());
        OwnershipOffer second = insert(newUser());
        OwnershipOffer third = insert(newUser());
        long watermark = offers.findExpiryWatermark();
        assertEquals(third.getId(), watermark);
        OwnershipOffer appended = insert(newUser());
        jdbc.update("""
            UPDATE ownership_offer SET created_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 8 DAY),
                expires_at = CASE id
                    WHEN ? THEN DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY)
                    WHEN ? THEN DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY)
                    ELSE DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 3 DAY) END
            WHERE id IN (?, ?, ?, ?)
            """, first.getId(), second.getId(), first.getId(), second.getId(), third.getId(), appended.getId());
        assertEquals(java.util.List.of(first.getId(), second.getId()),
            offers.expiredCandidates(first.getId() - 1, watermark, 2).stream().map(OwnershipOffer::getId).toList());
        assertEquals(java.util.List.of(third.getId()),
            offers.expiredCandidates(second.getId(), watermark, 2).stream().map(OwnershipOffer::getId).toList());
        assertTrue(offers.expiredCandidates(third.getId(), watermark, 2).isEmpty());
        assertEquals(appended.getId(), offers.findExpiryWatermark());
    }

    @Test void scopeAndDistinctPartyConstraintsRejectMalformedOffers() {
        OwnershipOffer offer = insert(newUser());
        DataAccessException scope = assertThrows(DataAccessException.class, () -> jdbc.update(
            "UPDATE ownership_offer SET scope_kind = 'ORGANIZATION' WHERE id = ?", offer.getId()));
        assertEquals(3819, ((SQLException) scope.getRootCause()).getErrorCode());
        assertTrue(scope.getRootCause().getMessage().contains("chk_ownership_offer_scope"));
        DataAccessException parties = assertThrows(DataAccessException.class, () -> jdbc.update(
            "UPDATE ownership_offer SET offeree_user_id = offeror_user_id WHERE id = ?", offer.getId()));
        assertEquals(3819, ((SQLException) parties.getRootCause()).getErrorCode());
        assertTrue(parties.getRootCause().getMessage().contains("chk_ownership_offer_parties"));
    }

    @Test void sevenDayDeadlineAndMembershipGenerationRoundTrip() {
        User target = newUser();
        OwnershipOffer offer = insert(target);
        assertEquals(workspaceMapper.getMembershipGenerationId(workspace.getId(), target.getId()), offer.getMembershipId());
        assertEquals(7L, jdbc.queryForObject("SELECT TIMESTAMPDIFF(DAY, created_at, expires_at) FROM ownership_offer WHERE id = ?", Long.class, offer.getId()));
        assertNotNull(offers.lockById(offer.getId()));
        assertNotNull(offers.lockByIdForShare(offer.getId()));
    }

    @Test void closureUsesOnlyImmutableOffereeForeignKeySupportAndExplicitActorCleanup() {
        assertEquals(java.util.List.of("idx_ownership_offer_offeree"), jdbc.queryForList(
            "SELECT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ownership_offer' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'offeree_user_id' ORDER BY INDEX_NAME", String.class));
        assertEquals(java.util.List.of("offeree_user_id", "id"), jdbc.queryForList(
            "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ownership_offer' AND INDEX_NAME = 'idx_ownership_offer_offeree' ORDER BY SEQ_IN_INDEX", String.class));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ownership_offer' AND COLUMN_NAME = 'offeree_user_id' AND REFERENCED_TABLE_NAME = 'app_user'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ownership_offer' AND COLUMN_NAME = 'closed_by_user_id' AND REFERENCED_TABLE_NAME IS NOT NULL", Integer.class));
    }
}
