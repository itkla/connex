package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.GeneratedDocumentTargetDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;

class GeneratedDocumentTargetTest extends AbstractServiceTest {
    @Autowired private DealDocumentService documentService;
    @Autowired private DealDocumentMapper documentMapper;
    @MockitoSpyBean private DealMapper parentMapper;

    @Test
    void returnsOnlyMinimalVisibleReferencesAndDeduplicatesIds() {
        DealDocument document = document();
        assertEquals(List.of(new GeneratedDocumentTargetDto(
            document.getId(), document.getDealId(), document.getTitle())),
            documentService.getTargets(List.of(document.getId(), document.getId())));
    }

    @Test
    void preservesTheExistingMembershipReadPolicyForViewers() {
        DealDocument document = document();
        User viewer = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), viewer.getId(), "viewer");
        authenticateAs(viewer, workspace.getId());
        assertEquals(List.of(new GeneratedDocumentTargetDto(
            document.getId(), document.getDealId(), document.getTitle())),
            documentService.getTargets(List.of(document.getId())));
    }

    @Test
    void omitsADocumentWhenTheExistingParentReadCannotSeeItsDeal() {
        DealDocument document = document();
        doReturn(null).when(parentMapper).getDealById(workspace.getId(), document.getDealId());
        assertTrue(documentService.getTargets(List.of(document.getId())).isEmpty());
    }

    @Test
    void omitsDeletedDocumentsWithoutDisclosingTheirTitle() {
        DealDocument document = document();
        documentMapper.delete(workspace.getId(), document.getId());
        assertTrue(documentService.getTargets(List.of(document.getId())).isEmpty());
    }

    @Test
    void omitsOtherWorkspaceDocumentsEvenForAMemberOfBothWorkspaces() {
        DealDocument document = document();
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        workspaceMapper.addMember(sibling.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, sibling.getId());
        assertTrue(documentService.getTargets(List.of(document.getId())).isEmpty());
    }

    @Test
    void refusesOverCapAndInvalidIdsBeforeLookingUpDocuments() {
        assertThrows(BadRequestException.class, () -> documentService.getTargets(
            IntStream.rangeClosed(1, 51).boxed().toList()));
        assertThrows(BadRequestException.class, () -> documentService.getTargets(
            java.util.Collections.nCopies(51, 1)));
        assertThrows(BadRequestException.class, () -> documentService.getTargets(List.of()));
        assertThrows(BadRequestException.class, () -> documentService.getTargets(List.of(0)));
        assertThrows(BadRequestException.class, () -> documentService.getTargets(List.of(-1)));
        assertThrows(BadRequestException.class, () -> documentService.getTargets(Arrays.asList(1, null)));
    }

    @Test
    void acceptsFiftyIdsAndOmitsUnknownIds() {
        DealDocument document = document();
        List<Integer> ids = IntStream.rangeClosed(1, 49).map(id -> Integer.MAX_VALUE - id).boxed()
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        ids.add(document.getId());
        assertEquals(1, documentService.getTargets(ids).size());
    }

    private DealDocument document() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        DealDocument document = new DealDocument();
        document.setWorkspaceId(workspace.getId());
        document.setDealId(deal.getId());
        document.setType("quote");
        document.setLocale("en");
        document.setStatus("draft");
        document.setVersion(1);
        document.setTitle("Quote " + unique());
        document.setContent("{\"private\":\"not projected\"}");
        document.setCurrency("JPY");
        document.setCreatedBy(currentUser.getId());
        documentMapper.insert(document);
        return document;
    }
}
