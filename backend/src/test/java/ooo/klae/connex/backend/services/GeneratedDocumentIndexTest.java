package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.GeneratedDocumentSummaryDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;

/** Cross-deal generated-document index: filters, ownership scope, and workspace isolation. */
class GeneratedDocumentIndexTest extends AbstractServiceTest {

    @Autowired private DealDocumentService documentService;
    @Autowired private DealDocumentMapper dealDocumentMapper;

    @Test
    void indexSpansEveryDealAndNamesEachParent() {
        Deal first = newDealFixture();
        Deal second = newDealFixture();
        DealDocument a = newDocument(workspace, first, "quote", "draft", 1);
        DealDocument b = newDocument(workspace, second, "contract", "final", 1);

        PageResponse<GeneratedDocumentSummaryDto> page = page(null, null, null, null);

        assertEquals(2, page.total());
        assertTrue(page.items().stream()
                .anyMatch(row -> row.id() == a.getId() && first.getName().equals(row.dealName())));
        assertTrue(page.items().stream()
                .anyMatch(row -> row.id() == b.getId() && second.getName().equals(row.dealName())));
    }

    @Test
    void statusAndTypeFiltersNarrowTheIndex() {
        Deal deal = newDealFixture();
        DealDocument draftQuote = newDocument(workspace, deal, "quote", "draft", 1);
        newDocument(workspace, deal, "contract", "final", 2);

        PageResponse<GeneratedDocumentSummaryDto> byStatus = page(null, List.of("draft"), null, null);
        assertEquals(1, byStatus.total());
        assertEquals(draftQuote.getId(), byStatus.items().getFirst().id());

        PageResponse<GeneratedDocumentSummaryDto> byType = page(null, null, List.of("contract"), null);
        assertEquals(1, byType.total());
        assertEquals("contract", byType.items().getFirst().type());
    }

    @Test
    void queryMatchesDocumentTitleAndParentDealName() {
        Deal deal = newDealFixture();
        DealDocument document = newDocument(workspace, deal, "quote", "draft", 1);

        assertEquals(1, page(document.getTitle(), null, null, null).total());
        assertEquals(1, page(deal.getName(), null, null, null).total());
        assertEquals(0, page("no-such-document-" + unique(), null, null, null).total());
    }

    @Test
    void memberScopeNarrowsByTheParentDealsOwner() {
        Deal owned = newDealFixture();
        Deal foreign = newDealFixture();
        User other = newUser();
        dealMapper.updateOwner(workspace.getId(), foreign.getId(), other.getId());
        newDocument(workspace, owned, "quote", "draft", 1);
        newDocument(workspace, foreign, "quote", "draft", 1);

        PageResponse<GeneratedDocumentSummaryDto> mine = documentService.getWorkspacePage(
                null, null, null, null,
                new MemberScope(MemberScope.Mode.ME, currentUser.getId(), List.of()), 25, 0);

        assertEquals(1, mine.total());
        assertEquals(owned.getId(), mine.items().getFirst().dealId());
    }

    @Test
    void indexNeverReturnsAnotherWorkspacesDocuments() {
        Deal deal = newDealFixture();
        newDocument(workspace, deal, "quote", "draft", 1);

        Workspace other = newSiblingWorkspace();
        authenticateAs(currentUser, other.getId());

        assertEquals(0, page(null, null, null, null).total());
        assertTrue(page(null, null, null, null).items().isEmpty());
    }

    @Test
    void dealFilterRestrictsToOneParent() {
        Deal first = newDealFixture();
        Deal second = newDealFixture();
        newDocument(workspace, first, "quote", "draft", 1);
        newDocument(workspace, second, "quote", "draft", 1);

        PageResponse<GeneratedDocumentSummaryDto> page = page(null, null, null, second.getId());

        assertEquals(1, page.total());
        assertEquals(second.getId(), page.items().getFirst().dealId());
    }

    private PageResponse<GeneratedDocumentSummaryDto> page(
            String query, List<String> statuses, List<String> types, Integer dealId) {
        return documentService.getWorkspacePage(
                query, statuses, types, dealId, MemberScope.allTeam(), 25, 0);
    }

    private Workspace newSiblingWorkspace() {
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        workspaceMapper.addMember(sibling.getId(), currentUser.getId(), "owner");
        return sibling;
    }

    private Deal newDealFixture() {
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        return newDeal(pipeline, stage, company);
    }

    private DealDocument newDocument(
            Workspace target, Deal deal, String type, String status, int version) {
        DealDocument document = new DealDocument();
        document.setWorkspaceId(target.getId());
        document.setDealId(deal.getId());
        document.setType(type);
        document.setLocale("en");
        document.setStatus(status);
        document.setVersion(version);
        document.setTitle("Doc " + unique());
        document.setContent("{}");
        document.setCurrency("JPY");
        document.setCreatedBy(currentUser.getId());
        dealDocumentMapper.insert(document);
        return document;
    }
}
