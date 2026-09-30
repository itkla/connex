package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.SegmentCatalogDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;

class SegmentServiceCatalogTest {
    private final SegmentService segmentService = new SegmentService(
        mock(WorkspaceService.class), mock(AuthService.class), mock(ScoringService.class),
        mock(DealRiskService.class), mock(SegmentMapper.class), mock(PersonEdgeReadService.class),
        mock(PersonMapper.class), mock(TagMapper.class), new SegmentCatalog());

    @Test
    void catalog_company_exposesFieldsPredicatesAndLimits() {
        SegmentCatalogDto dto = segmentService.catalog("company");

        assertEquals("company", dto.recordType());
        assertEquals(List.of("industry", "name", "website", "phone", "owner", "tag", "created", "updated"),
            dto.fields().stream().map(SegmentCatalogDto.CatalogField::field).toList());
        SegmentCatalogDto.CatalogField industry = dto.fields().stream()
            .filter(f -> f.field().equals("industry")).findFirst().orElseThrow();
        assertEquals("string", industry.kind());
        assertEquals("industries", industry.valueSource());
        assertEquals(List.of("equals", "contains", "starts_with", "is_set"), industry.operators());
        assertEquals(List.of("warm_intro_available", "open_deal", "cooling", "no_activity", "has_attachment",
                "warmth_hot", "warmth_warm", "warmth_cool", "warmth_cold", "warmth_rising", "going_cold"),
            dto.predicates().stream().map(SegmentCatalogDto.CatalogPredicate::key).toList());
        SegmentCatalogDto.CatalogPredicate noActivity = dto.predicates().stream()
            .filter(p -> p.key().equals("no_activity")).findFirst().orElseThrow();
        assertTrue(noActivity.acceptsDays());
        assertEquals(30, noActivity.defaultDays());
        assertEquals(3650, noActivity.maxDays());
        assertFalse(dto.predicates().stream().filter(p -> p.key().equals("open_deal"))
            .findFirst().orElseThrow().acceptsDays());
        assertNotNull(dto.limits());
        assertEquals(32, dto.limits().maxConditions());
        assertEquals(4, dto.limits().maxDepth());
    }

    @Test
    void catalog_deal_hasStatusEnumOptionsAndExistencePredicates() {
        SegmentCatalogDto dto = segmentService.catalog("deal");

        assertEquals(List.of("has_open_task", "overdue_task", "recent_meeting", "has_note", "has_attachment",
                "at_risk", "risk_high", "risk_close_overdue", "risk_closing_soon", "risk_stalled",
                "risk_stakeholder_cold", "risk_no_stakeholders"),
            dto.predicates().stream().map(SegmentCatalogDto.CatalogPredicate::key).toList());
        assertEquals(List.of("open", "won", "lost"), dto.enumOptions().get("status"));
        SegmentCatalogDto.CatalogField stage = dto.fields().stream()
            .filter(f -> f.field().equals("stage")).findFirst().orElseThrow();
        assertEquals("id", stage.kind());
        assertEquals("stages", stage.valueSource());
        assertEquals(List.of("is", "in"), stage.operators());
    }

    @Test
    void catalog_person_hasExistencePredicatesAndNoEnumOptions() {
        SegmentCatalogDto dto = segmentService.catalog("person");

        assertEquals(List.of("has_open_task", "overdue_task", "recent_meeting", "has_note", "has_attachment",
                "warmth_hot", "warmth_warm", "warmth_cool", "warmth_cold", "warmth_rising", "going_cold"),
            dto.predicates().stream().map(SegmentCatalogDto.CatalogPredicate::key).toList());
        assertTrue(dto.enumOptions().isEmpty());
    }

    @Test
    void catalog_unsupportedRecordType_throws() {
        assertThrows(BadRequestException.class, () -> segmentService.catalog("task"));
    }
}
