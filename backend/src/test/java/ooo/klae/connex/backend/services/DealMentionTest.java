package ooo.klae.connex.backend.services;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;

class DealMentionTest extends AbstractServiceTest {

    @Autowired DealService dealService;
    @Autowired NoteService noteService;
    @Autowired ReferenceService referenceService;
    @Autowired NotificationMapper notificationMapper;
    @Autowired CompanyService companyService;
    @Autowired PersonService personService;
    @Autowired PipelineService pipelineService;
    @Autowired TagService tagService;
    @Autowired SearchService searchService;

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

    @Test
    void getDealById_redactsPrivateNoteTargetsForAnotherMember() {
        Note privateDraft = new Note();
        privateDraft.setContent("private deal context");
        privateDraft.setVisibility("private");
        Note privateNote = noteService.create(privateDraft);
        Deal deal = newOpenDeal();
        dealService.close(deal.getId(), false,
            "Lost after [Secret Review](note:" + privateNote.getId() + ")", null);
        User other = newUser();
        authenticateAs(other, workspace.getId());

        Deal fetched = dealService.getDealById(deal.getId());

        assertEquals("Lost after (private note)", fetched.getClosedReason());
        assertFalse(fetched.getReferences().stream()
            .anyMatch(reference -> "note".equals(reference.getRefType())));
    }

    @Test
    void dealCollectionReads_redactPrivateNoteTargetsForAnotherMember() {
        Note privateDraft = new Note();
        privateDraft.setContent("private deal list context");
        privateDraft.setVisibility("private");
        Note privateNote = noteService.create(privateDraft);
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Person person = newPerson(company);
        Tag tag = newTag();
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), "decision_maker");
        dealMapper.addTag(workspace.getId(), deal.getId(), tag.getId());
        dealService.close(deal.getId(), false,
            "Lost after [Secret Review](note:" + privateNote.getId() + ")", null);
        User other = newUser();
        authenticateAs(other, workspace.getId());

        List<Deal> fetched = List.of(
            findDeal(dealService.getAllDeals(), deal.getId()),
            findDeal(dealService.getDealsPage(
                "%" + deal.getName() + "%", null, null, null, null, null, null, null, 25, 0), deal.getId()),
            findDeal(companyService.getDealsByCompanyId(company.getId(), 100), deal.getId()),
            findDeal(personService.getDealsByPersonId(person.getId()), deal.getId()),
            findDeal(pipelineService.getDealsByPipelineId(pipeline.getId()), deal.getId()),
            findDeal(pipelineService.getDealsByStageId(stage.getId()), deal.getId()),
            findDeal(tagService.getDealsByTagId(tag.getId()), deal.getId()));

        for (Deal candidate : fetched) {
            assertEquals("Lost after (private note)", candidate.getClosedReason());
            assertFalse(candidate.getReferences().stream()
                .anyMatch(reference -> "note".equals(reference.getRefType())));
        }
        assertEquals("Lost after (private note)", searchService.search(deal.getName()).getDeals().stream()
            .filter(candidate -> candidate.getId() == deal.getId())
            .findFirst()
            .orElseThrow()
            .getClosedReason());
    }

    @Test
    void genericDealReadsOmitNoteIdsWhileDedicatedReadsEnforceVisibility() {
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Person person = newPerson(company);
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), "decision_maker");

        Note privateDraft = new Note();
        privateDraft.setContent("private linked note");
        privateDraft.setVisibility("private");
        privateDraft.setDeal(deal);
        Note privateNote = noteService.create(privateDraft);
        Note workspaceDraft = new Note();
        workspaceDraft.setContent("workspace linked note");
        workspaceDraft.setVisibility("workspace");
        workspaceDraft.setDeal(deal);
        Note workspaceNote = noteService.create(workspaceDraft);
        assertNull(dealService.getDealById(deal.getId()).getNotes());
        assertEquals(
            List.of(privateNote.getId(), workspaceNote.getId()).stream().sorted().toList(),
            dealService.getNotesByDealId(deal.getId()).stream().map(Note::getId).sorted().toList());

        User other = newUser();
        authenticateAs(other, workspace.getId());
        List<Deal> fetched = List.of(
            dealService.getDealById(deal.getId()),
            findDeal(dealService.getAllDeals(), deal.getId()),
            findDeal(companyService.getDealsByCompanyId(company.getId(), 100), deal.getId()),
            findDeal(personService.getDealsByPersonId(person.getId()), deal.getId()),
            findDeal(Arrays.asList(personService.getPersonById(person.getId()).getDeals()), deal.getId()));

        for (Deal candidate : fetched) {
            assertNull(candidate.getNotes());
        }
        DealDto searchResult = searchService.search(deal.getName()).getDeals().stream()
            .filter(candidate -> candidate.getId() == deal.getId())
            .findFirst()
            .orElseThrow();
        assertNull(searchResult.getNoteIds());
        assertEquals(
            List.of(workspaceNote.getId()),
            dealService.getNotesByDealId(deal.getId()).stream().map(Note::getId).toList());
    }

    private static Deal findDeal(List<Deal> deals, int dealId) {
        return deals.stream()
            .filter(candidate -> candidate.getId() == dealId)
            .findFirst()
            .orElseThrow();
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
