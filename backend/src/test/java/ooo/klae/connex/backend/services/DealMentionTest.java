package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.NotificationMapper;

class DealMentionTest extends AbstractServiceTest {

    @Autowired DealService dealService;
    @Autowired ReferenceService referenceService;
    @Autowired NotificationMapper notificationMapper;

    private String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }

    private Deal newOpenDeal() {
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        return newDeal(pipeline, stage, company);
    }

    private List<Notification> mentions(int recipientId) {
        return notificationMapper.findPage(recipientId, null, "deal", null, null, 50, 0)
            .stream().filter(n -> "deal.mention".equals(n.getType())).toList();
    }

    /**
     * Closing a deal with a mention in the reason notifies the member and returns resolved references.
     */
    @Test
    void close_withMentionInReason_notifiesAndHydratesReferences() {
        Deal deal = newOpenDeal();
        User mentioned = newUser();

        Deal closed = dealService.close(deal.getId(), false,
            "Lost — " + mention("Mentioned", mentioned) + " to follow up", null);

        assertNotNull(closed.getReferences());
        assertEquals(1, closed.getReferences().size());
        assertEquals(mentioned.getId(), closed.getReferences().get(0).getRefId());
        assertEquals("user", closed.getReferences().get(0).getRefType());

        List<Notification> notifications = mentions(mentioned.getId());
        assertEquals(1, notifications.size());
        Notification notification = notifications.get(0);
        assertEquals("deal.mention", notification.getType());
        assertEquals("deal", notification.getCategory());
        assertEquals(currentUser.getId(), notification.getActorId());
        assertEquals("deal", notification.getSourceType());
        assertEquals(deal.getId(), notification.getSourceId());
        assertEquals("deal.mention:" + deal.getId() + ":" + mentioned.getId(), notification.getDedupeKey());
    }

    /**
     * The author is never notified for mentioning themselves.
     */
    @Test
    void close_selfMention_doesNotNotify() {
        Deal deal = newOpenDeal();
        dealService.close(deal.getId(), true, "Won thanks to " + mention("Me", currentUser), null);

        assertTrue(mentions(currentUser.getId()).isEmpty());
    }

    /**
     * Reopening a deal clears its closedReason, so its references are purged (the deal-specific lifecycle).
     */
    @Test
    void reopen_purgesClosedReasonReferences() {
        Deal deal = newOpenDeal();
        User mentioned = newUser();
        dealService.close(deal.getId(), false, "Lost — " + mention("Mentioned", mentioned), null);
        assertEquals(1,
            referenceService.referencesFor(workspace.getId(), ReferenceService.SOURCE_DEAL, deal.getId()).size());

        dealService.reopen(deal.getId());

        assertTrue(referenceService
            .referencesFor(workspace.getId(), ReferenceService.SOURCE_DEAL, deal.getId()).isEmpty());
        assertTrue(dealService.getDealById(deal.getId()).getReferences().isEmpty());
    }

    /**
     * Deleting a deal purges its closedReason references.
     */
    @Test
    void delete_purgesReferences() {
        Deal deal = newOpenDeal();
        User mentioned = newUser();
        dealService.close(deal.getId(), false, mention("Mentioned", mentioned), null);
        assertEquals(1,
            referenceService.referencesFor(workspace.getId(), ReferenceService.SOURCE_DEAL, deal.getId()).size());

        dealService.delete(deal.getId());

        assertTrue(referenceService
            .referencesFor(workspace.getId(), ReferenceService.SOURCE_DEAL, deal.getId()).isEmpty());
    }

    /**
     * The deal detail read hydrates closedReason references so mentions render as chips.
     */
    @Test
    void getDealById_hydratesClosedReasonReferences() {
        Deal deal = newOpenDeal();
        User mentioned = newUser();
        dealService.close(deal.getId(), false, mention("Mentioned", mentioned), null);

        Deal fetched = dealService.getDealById(deal.getId());
        assertNotNull(fetched.getReferences());
        assertEquals(1, fetched.getReferences().size());
        assertEquals(mentioned.getId(), fetched.getReferences().get(0).getRefId());
    }

    /**
     * Creating an already-closed deal with a mention in the reason syncs + notifies too.
     */
    @Test
    void create_closedDealWithMentionInReason_notifiesAndHydrates() {
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        User mentioned = newUser();

        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setValue(1000.0);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        deal.setWon(false);
        deal.setClosedReason("Lost — " + mention("Mentioned", mentioned));

        Deal created = dealService.create(deal);

        assertNotNull(created.getReferences());
        assertEquals(1, created.getReferences().size());
        assertEquals(mentioned.getId(), created.getReferences().get(0).getRefId());
        assertEquals(1, mentions(mentioned.getId()).size());
    }
}
