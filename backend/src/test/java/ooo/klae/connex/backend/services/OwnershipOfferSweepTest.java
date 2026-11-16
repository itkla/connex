package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Pins bounded progress and retry fairness when entire pages have unavailable placements. */
@ExtendWith(MockitoExtension.class)
class OwnershipOfferSweepTest {
    @Mock private OwnershipOfferMapper mapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OwnershipOfferTransaction transaction;
    @Mock private TenantCatalogResolver catalogResolver;
    private final TenantContext context = new TenantContext();
    private final NavigableMap<Long, OwnershipOffer> pending = new TreeMap<>();
    private final List<Long> closed = new ArrayList<>();
    private final List<List<Long>> pages = new ArrayList<>();
    private OwnershipOfferService service;

    @BeforeEach void sweep() {
        context.set(3, 4, 5, "member", "foreign_catalog");
        service = newService();
        when(mapper.findExpiryWatermark()).thenAnswer(call -> {
            assertNull(context.getCatalog());
            return pending.isEmpty() ? null : pending.lastKey();
        });
        when(mapper.expiredCandidates(anyLong(), anyLong(), eq(100))).thenAnswer(call -> {
            assertNull(context.getCatalog());
            long cursor = call.getArgument(0);
            long watermark = call.getArgument(1);
            List<OwnershipOffer> page = pending.values().stream()
                .filter(offer -> offer.getId() > cursor && offer.getId() <= watermark).limit(100).toList();
            pages.add(page.stream().map(OwnershipOffer::getId).toList());
            return page;
        });
        when(workspaceMapper.getOrgId(anyInt())).thenAnswer(call -> {
            assertNull(context.getCatalog());
            return call.getArgument(0, Integer.class) == 13 ? 7 : 8;
        });
        when(catalogResolver.resolveCatalog(anyInt())).thenAnswer(call -> {
            if (call.getArgument(0, Integer.class) == 7) {
                throw new IllegalStateException("Placement unavailable");
            }
            return "reachable_catalog";
        });
        doAnswer(call -> {
            assertEquals("reachable_catalog", context.getCatalog());
            long id = call.getArgument(0);
            assertNotNull(pending.remove(id));
            closed.add(id);
            return null;
        }).when(transaction).reconcile(anyLong());
    }

    @AfterEach void restoreSelection() {
        assertEquals("foreign_catalog", context.getCatalog());
        assertEquals(3, context.getWorkspaceId());
        assertEquals(4, context.getOrgId());
        assertEquals(5, context.getUserId());
        context.clear();
    }

    @Test void scheduledPassesReachFencedRemaindersBeyondMoreThanOnePageOfPermanentFailures() {
        for (long id = 1; id <= 158; id++) {
            add(id, id <= 101);
        }
        when(mapper.fencedOrganizationOffers(8, 50)).thenAnswer(call -> pending.values().stream()
            .filter(offer -> offer.getOrgId() == 8).limit(50).toList());
        service.invalidateFencedOrganization(8);
        assertEquals(50, closed.size());
        assertEquals(7, pending.values().stream().filter(offer -> offer.getOrgId() == 8).count());

        OwnershipOfferScheduler scheduler = new OwnershipOfferScheduler(service, mock(OwnershipOfferDeliveryService.class));
        scheduler.reconcile();
        assertEquals(100, pages.getFirst().size());
        assertEquals(1L, pages.getFirst().getFirst());
        assertEquals(100L, pages.getFirst().getLast());
        assertEquals(50, closed.size());
        for (long id = 159; id <= 369; id++) {
            add(id, false);
        }

        scheduler.reconcile();
        assertEquals(List.of(101L, 152L, 153L, 154L, 155L, 156L, 157L, 158L), pages.get(1));
        assertEquals(57, closed.size());
        assertTrue(closed.containsAll(List.of(152L, 153L, 154L, 155L, 156L, 157L, 158L)));
        verify(mapper, times(1)).findExpiryWatermark();
        verify(mapper).expiredCandidates(100, 158, 100);

        scheduler.reconcile();
        assertTrue(pages.get(2).isEmpty());
        scheduler.reconcile();
        assertEquals(pages.getFirst(), pages.get(3));
        verify(mapper, times(2)).findExpiryWatermark();
        verify(mapper).expiredCandidates(0, 369, 100);
        verify(catalogResolver, times(201)).resolveCatalog(7);
        verify(transaction, times(57)).reconcile(anyLong());
    }

    @Test void deletedWatermarkWrapsAndRestartDiscardsOnlyTheCursor() {
        add(1, true);
        add(2, false);
        service.expire();
        assertEquals(List.of(2L), closed);
        assertFalse(pending.containsKey(2L));
        add(3, false);
        service.expire();
        assertTrue(pages.get(1).isEmpty());
        service.expire();
        assertEquals(List.of(2L, 3L), closed);
        verify(catalogResolver, times(2)).resolveCatalog(7);

        service = newService();
        service.expire();
        verify(mapper).expiredCandidates(0, 1, 100);
        verify(catalogResolver, times(3)).resolveCatalog(7);
        assertEquals(List.of(1L), new ArrayList<>(pending.keySet()));
    }

    private void add(long id, boolean unavailable) {
        OwnershipOffer offer = new OwnershipOffer();
        offer.setId(id); offer.setOrgId(unavailable ? 7 : 8); offer.setWorkspaceId(unavailable ? 13 : 14);
        offer.setDeliveryWorkspaceId(offer.getWorkspaceId()); offer.setOffereeUserId(2);
        pending.put(id, offer);
    }

    private OwnershipOfferService newService() {
        return new OwnershipOfferService(mapper, mock(OrgMemberMapper.class), workspaceMapper,
            mock(OrganizationMapper.class), mock(UserMapper.class), transaction, mock(OwnershipOfferEligibility.class),
            new TenantWorkScope(context, catalogResolver, workspaceMapper), context,
            mock(TenantLifecycleControlMapper.class), mock(TenantLifecycleAccess.class),
            mock(PrivilegedAccountService.class), mock(AuditService.class), mock(WorkspaceService.class), mock(OrgMemberService.class));
    }
}
