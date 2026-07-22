package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.DealSegmentQueryRequest;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.services.ExportService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class ExportControllerTest {
    @Mock private ExportService exportService;
    @Mock private MemberScopeResolver memberScopeResolver;
    @Mock private WorkspaceService workspaceService;

    @Test
    void segmentDealExportPreservesTheValidatedBodyScope() {
        ExportController controller = new ExportController(
            exportService, memberScopeResolver, workspaceService);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of());
        DealSegmentQueryRequest request = new DealSegmentQueryRequest();
        request.setDefinition(definition);
        request.setQ("Acme");
        request.setCurrency("JPY");
        request.setPipelineId(List.of(2, 2, 3));
        request.setStageId(List.of(5));
        request.setCompanyId(List.of(7));
        request.setNoCompany(true);
        request.setStatus(List.of("closed"));
        request.setRisk(List.of("high", "none"));
        request.setScope("me");
        MemberScope memberScope = MemberScope.fromRequest("me", null, 11);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(memberScopeResolver.resolve("me", null, 11)).thenReturn(memberScope);
        when(exportService.exportSegmentDeals(
            definition, "%Acme%", "JPY", List.of(2, 3), List.of(5), List.of(7), true,
            List.of("won", "lost"), List.of("high", "none"), memberScope))
            .thenReturn("id,name\n17,Deal");

        var response = controller.exportSegmentDeals(request);

        assertEquals("attachment; filename=\"deals.csv\"",
            response.getHeaders().getFirst("Content-Disposition"));
        byte[] csv = "id,name\n17,Deal".getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[csv.length + 3];
        expected[0] = (byte) 0xEF;
        expected[1] = (byte) 0xBB;
        expected[2] = (byte) 0xBF;
        System.arraycopy(csv, 0, expected, 3, csv.length);
        assertArrayEquals(expected, response.getBody());
        verify(exportService).exportSegmentDeals(
            definition, "%Acme%", "JPY", List.of(2, 3), List.of(5), List.of(7), true,
            List.of("won", "lost"), List.of("high", "none"), memberScope);
    }

    @Test
    void productExportNormalizesSearchAndReturnsBomPrefixedCsv() {
        ExportController controller = new ExportController(
            exportService, memberScopeResolver, workspaceService);
        when(exportService.exportProducts("%100\\%\\_ready%"))
            .thenReturn("id,name\r\n7,Product\r\n");

        var response = controller.exportProducts("  100%_ready  ");

        assertEquals("attachment; filename=\"products.csv\"",
            response.getHeaders().getFirst("Content-Disposition"));
        assertEquals("text/csv;charset=UTF-8", response.getHeaders().getFirst("Content-Type"));
        byte[] csv = "id,name\r\n7,Product\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[csv.length + 3];
        expected[0] = (byte) 0xEF;
        expected[1] = (byte) 0xBB;
        expected[2] = (byte) 0xBF;
        System.arraycopy(csv, 0, expected, 3, csv.length);
        assertArrayEquals(expected, response.getBody());
        verify(exportService).exportProducts("%100\\%\\_ready%");
    }
}
