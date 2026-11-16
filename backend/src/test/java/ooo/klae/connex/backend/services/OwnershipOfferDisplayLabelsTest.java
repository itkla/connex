package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.function.Supplier;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.OwnershipOfferAcceptanceDto;
import ooo.klae.connex.backend.dto.OwnershipOfferDto;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Optional card labels retain field presence and caller-specific contact visibility. */
@ExtendWith(MockitoExtension.class)
class OwnershipOfferDisplayLabelsTest {
    @Mock private OwnershipOfferMapper mapper;
    @Mock private OrgMemberMapper orgMemberMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private UserMapper userMapper;
    @Mock private OwnershipOfferEligibility eligibility;
    @Mock private OwnershipOfferTransaction transaction;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private PrivilegedAccountService privilegedAccountService;
    @InjectMocks private OwnershipOfferService service;
    private final ObjectMapper json = JsonMapper.builder()
        .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL).build();

    @BeforeEach void controlReads() {
        when(tenantWorkScope.unroutedRead(any())).thenAnswer(invocation -> {
            ControlPlaneRead<?> read = invocation.getArgument(0);
            return read.read();
        });
    }

    @Test void missingLabelsRemainNullAndPresentInSelfCards() {
        OwnershipOffer offer = offer(1L, 2);
        when(mapper.listForOfferee(2)).thenReturn(List.of(offer));
        when(eligibility.isValid(offer)).thenReturn(true);
        assertNullLabels(service.listForSelf(2).getFirst());
    }

    @Test void selfLabelsNeverReadAnotherOffereesContact() {
        OwnershipOffer offer = offer(1L, 3);
        when(mapper.listForOfferee(2)).thenReturn(List.of(offer));
        when(eligibility.isValid(offer)).thenReturn(true);
        when(userMapper.getUserById(1)).thenReturn(party(1, "Offeror", "private-offeror@example.test"));
        OwnershipOfferDto card = service.listForSelf(2).getFirst();
        assertEquals("Offeror", card.offerorDisplayName());
        assertNull(card.offereeDisplayName());
        assertNull(card.offereeEmail());
        verify(userMapper, never()).getUserById(3);
        assertFalse(json.writeValueAsString(card).contains("private-offeror@example.test"));
    }

    @Test void scopeOwnersSeeAllLabelsWithResponseLocalReadCaches() {
        OwnershipOffer first = offer(1L, 2);
        OwnershipOffer second = offer(2L, 3);
        when(orgMemberMapper.getRole(7, 1)).thenReturn("owner");
        when(mapper.listForScope("ORGANIZATION", 7)).thenReturn(List.of(first, second));
        when(eligibility.isValid(any())).thenReturn(true);
        Organization org = new Organization(); org.setId(7); org.setName("Organization label");
        when(organizationMapper.getById(7)).thenReturn(org);
        when(userMapper.getUserById(1)).thenReturn(party(1, "Offeror", "private-offeror@example.test"));
        when(userMapper.getUserById(2)).thenReturn(party(2, "First offeree", "first@example.test"));
        when(userMapper.getUserById(3)).thenReturn(party(3, "Second offeree", "second@example.test"));
        List<OwnershipOfferDto> cards = service.listForScope("ORGANIZATION", 7, 1);
        assertEquals("Organization label", cards.getFirst().scopeName());
        assertEquals("Offeror", cards.getFirst().offerorDisplayName());
        assertEquals("First offeree", cards.getFirst().offereeDisplayName());
        assertEquals("first@example.test", cards.getFirst().offereeEmail());
        assertEquals("Second offeree", cards.getLast().offereeDisplayName());
        assertEquals("second@example.test", cards.getLast().offereeEmail());
        verify(organizationMapper).getById(7);
        verify(userMapper).getUserById(1);
    }

    @Test void missingLabelsDoNotFailAnAcceptedTransition() {
        OwnershipOffer offer = offer(1L, 2); offer.setState("ACCEPTED");
        when(mapper.findById(1L)).thenReturn(offer);
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        when(transaction.accept(1L, 2, false)).thenReturn(new OwnershipOfferAcceptanceDto(
            OwnershipOfferDto.from(offer), null, PrivilegedMfaStanding.SATISFIED, PrivilegedMfaStanding.SATISFIED));
        when(privilegedAccountService.standing(2)).thenReturn(PrivilegedMfaStanding.SATISFIED);
        when(privilegedAccountService.enforcedStanding(2)).thenReturn(PrivilegedMfaStanding.SATISFIED);
        OwnershipOfferAcceptanceDto accepted = service.accept(1L, 2, false);
        assertEquals("ACCEPTED", accepted.offer().state());
        assertEquals(PrivilegedMfaStanding.SATISFIED, accepted.standing());
        assertNullLabels(accepted.offer());
    }

    private void assertNullLabels(OwnershipOfferDto card) {
        var tree = json.readTree(json.writeValueAsString(card));
        for (String field : List.of("scopeName", "offerorDisplayName", "offereeDisplayName", "offereeEmail")) {
            assertFalse(tree.path(field).isMissingNode(), field);
            assertTrue(tree.path(field).isNull(), field);
        }
    }

    private OwnershipOffer offer(long id, int offereeId) {
        OwnershipOffer offer = new OwnershipOffer(); offer.setId(id); offer.setOrgId(7);
        offer.setScopeKind("ORGANIZATION"); offer.setOfferorUserId(1); offer.setOffereeUserId(offereeId);
        offer.setState("OPEN"); offer.setCreatedAt("2026-10-10 00:00:00"); offer.setExpiresAt("2026-10-17 00:00:00");
        return offer;
    }

    private User party(int id, String name, String email) {
        User user = new User(); user.setId(id); user.setDisplayName(name); user.setEmail(email); return user;
    }
}
