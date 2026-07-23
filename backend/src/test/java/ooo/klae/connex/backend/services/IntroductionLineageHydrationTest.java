package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.dto.IntroductionDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies bounded control-plane label hydration for the tenant-local introduction feed. */
@ExtendWith(MockitoExtension.class)
class IntroductionLineageHydrationTest {
    @Mock private IntroductionMapper introductionMapper;
    @Mock private UserMapper userMapper;
    @Mock private PersonEdgeMapper edgeMapper;
    @Mock private PersonMapper personMapper;
    @Mock private ScoringService scoringService;
    @Mock private WarmPathService warmPathService;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private Clock clock;
    @Mock private ReferenceService referenceService;
    @Mock private NotificationDelivery notificationDelivery;
    @Mock private NotificationPreferenceService notificationPreferenceService;
    @Mock private ObjectMapper objectMapper;
    @Mock private TenantWorkScope tenantWorkScope;

    private IntroductionService service;

    @BeforeEach
    void setUp() {
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        service = new IntroductionService(
            introductionMapper,
            userMapper,
            edgeMapper,
            personMapper,
            scoringService,
            warmPathService,
            workspaceService,
            authService,
            clock,
            referenceService,
            notificationDelivery,
            notificationPreferenceService,
            objectMapper,
            tenantWorkScope);
    }

    @Test
    void lineageHydratesDistinctUserLabelsWithoutDroppingOrReorderingRows() {
        IntroductionDto first = introduction(1, 7, "stale");
        IntroductionDto second = introduction(2, 8, null);
        IntroductionDto third = introduction(3, 8, null);
        IntroductionDto fourth = introduction(4, null, "stale");
        List<IntroductionDto> items = List.of(first, second, third, fourth);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(introductionMapper.findLineage(5, 25, 0)).thenReturn(items);
        when(introductionMapper.countLineage(5)).thenReturn(4L);
        when(userMapper.getDisplayNamesByIds(List.of(7, 8)))
            .thenReturn(List.of(new UserDisplayNameDto(8, "User Eight")));
        when(referenceService.referencesBySource(
                5, ReferenceService.SOURCE_INTRODUCTION, List.of(1, 2, 3, 4)))
            .thenReturn(Map.of());
        when(referenceService.redactInvisibleNoteTargets(anyInt(), any()))
            .thenAnswer(invocation -> invocation.getArgument(1));

        PageResponse<IntroductionDto> page = service.getLineage(1, 25);

        assertEquals(List.of(1, 2, 3, 4), page.items().stream().map(IntroductionDto::getId).toList());
        assertEquals(4, page.total());
        assertEquals(java.util.Arrays.asList(null, "User Eight", "User Eight", null),
            page.items().stream().map(IntroductionDto::getIntroducerName).toList());
        verify(tenantWorkScope).unrouted(any());
        verify(userMapper).getDisplayNamesByIds(List.of(7, 8));
    }

    private static IntroductionDto introduction(int id, Integer introducerId, String introducerName) {
        IntroductionDto introduction = new IntroductionDto();
        introduction.setId(id);
        introduction.setIntroducerId(introducerId);
        introduction.setIntroducerName(introducerName);
        return introduction;
    }
}
