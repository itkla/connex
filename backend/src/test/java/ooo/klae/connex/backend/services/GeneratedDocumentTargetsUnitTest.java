package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.dto.GeneratedDocumentTargetDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;

@ExtendWith(MockitoExtension.class)
class GeneratedDocumentTargetsUnitTest {
    @Mock private DealDocumentMapper documentMapper;
    @Mock private DealMapper dealMapper;
    @Mock private WorkspaceService workspaceService;
    @InjectMocks private DealDocumentService documentService;

    @Test
    void neverReturnsATitleWhenTheParentGateRefusesTheDeal() {
        GeneratedDocumentTargetDto visible = new GeneratedDocumentTargetDto(61, 41, "Visible quote");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(documentMapper.getTargets(7, List.of(61, 62, 63))).thenReturn(List.of(
            visible, new GeneratedDocumentTargetDto(62, 42, "Hidden private title")));
        when(dealMapper.getDealById(7, 41)).thenReturn(new Deal());
        assertEquals(List.of(visible), documentService.getTargets(List.of(61, 62, 63)));
    }

    @Test
    void validatesBeforeTenantReadsAndCapsBeforeDeduplication() {
        assertThrows(BadRequestException.class, () -> documentService.getTargets(Collections.nCopies(51, 61)));
        assertThrows(BadRequestException.class, () -> documentService.getTargets(null));
        verifyNoInteractions(workspaceService, documentMapper, dealMapper);
    }

    @Test
    void pinsTheQueryAndChecksEachDistinctParentOnlyOnce() {
        GeneratedDocumentTargetDto first = new GeneratedDocumentTargetDto(61, 41, "First quote");
        GeneratedDocumentTargetDto second = new GeneratedDocumentTargetDto(62, 41, "Second quote");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(documentMapper.getTargets(7, List.of(61, 62))).thenReturn(List.of(first, second));
        when(dealMapper.getDealById(7, 41)).thenReturn(new Deal());
        assertEquals(List.of(first, second), documentService.getTargets(List.of(61, 62, 61)));
        verify(dealMapper).getDealById(7, 41);
    }
}
