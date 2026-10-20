package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;

class SegmentServiceBoundedTest {

    @Test
    void evaluatesOnlyCeilingPlusOneSqlCandidates() {
        SegmentMapper segmentMapper = mock(SegmentMapper.class);
        SegmentService service = new SegmentService(
            mock(WorkspaceService.class),
            mock(AuthService.class),
            mock(ScoringService.class),
            mock(DealRiskService.class),
            segmentMapper,
            mock(PersonEdgeReadService.class),
            mock(PersonMapper.class),
            mock(TagMapper.class),
            new SegmentCatalog());
        List<Integer> firstPage = IntStream.rangeClosed(1, 201).boxed().toList();
        when(segmentMapper.maximumEntityId(7, "company")).thenReturn(202);
        when(segmentMapper.entityIdsPage(7, "company", 0, 202, 201))
            .thenReturn(firstPage);
        when(segmentMapper.companyIdsMatching(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> params = invocation.getArgument(0);
            return ((List<?>) params.get("candidateIds")).stream()
                .map(Integer.class::cast)
                .toList();
        });
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("is_set");
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));

        List<Integer> result = service.evaluate(7, 17, "company", definition, 201);

        assertEquals(201, result.size());
        assertEquals(201, result.getLast());
        verify(segmentMapper).entityIdsPage(7, "company", 0, 202, 201);
    }

    @Test
    void warmIntroScopesPeopleAndEdgesToTheCandidatePage() {
        SegmentMapper segmentMapper = mock(SegmentMapper.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        PersonEdgeReadService edgeReader = mock(PersonEdgeReadService.class);
        SegmentService service = new SegmentService(
            mock(WorkspaceService.class),
            mock(AuthService.class),
            mock(ScoringService.class),
            mock(DealRiskService.class),
            segmentMapper,
            edgeReader,
            personMapper,
            mock(TagMapper.class),
            new SegmentCatalog());
        when(segmentMapper.maximumEntityId(7, "company")).thenReturn(3);
        when(segmentMapper.entityIdsPage(7, "company", 0, 3, 3))
            .thenReturn(List.of(1, 2, 3));
        when(segmentMapper.companyIdsWithWarmIntro(
                eq(7), eq(17), argThat(ids -> new HashSet<>(ids).equals(Set.of(1, 2, 3))), eq(2)))
            .thenReturn(List.of(2));
        SegmentCondition condition = new SegmentCondition();
        condition.setType("predicate");
        condition.setKey("warm_intro_available");
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));

        assertEquals(List.of(2), service.evaluate(7, 17, "company", definition, 3));

        verify(segmentMapper).companyIdsWithWarmIntro(
            eq(7), eq(17), argThat(ids -> new HashSet<>(ids).equals(Set.of(1, 2, 3))), eq(2));
        verifyNoInteractions(personMapper, edgeReader);
    }
}
