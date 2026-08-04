package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.OrganizationIdentityDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutDto;
import ooo.klae.connex.backend.dto.RenameOrganizationRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrganizationService;

/** HTTP delegation contract for organization identity and layout endpoints. */
@ExtendWith(MockitoExtension.class)
class OrganizationControllerTest {
    @Mock private OrganizationService organizationService;
    @Mock private AuthService authService;

    private OrganizationController controller;

    @BeforeEach
    void setUp() {
        controller = new OrganizationController(organizationService, authService);
        User user = new User();
        user.setId(7);
        when(authService.getCurrentUser()).thenReturn(user);
    }

    @Test
    void renameDelegatesCanonicalIdentityMutation() {
        RenameOrganizationRequest request = new RenameOrganizationRequest();
        request.setName("Renamed Org");
        request.setExpectedName("Original Org");
        request.setExpectedIdentityVersion(4L);
        OrganizationIdentityDto expected = new OrganizationIdentityDto(
            3, "Renamed Org", "immutable", 5L, "2026-08-03 12:00:00");
        when(organizationService.rename(3, 7, "Renamed Org", "Original Org", 4L)).thenReturn(expected);

        OrganizationIdentityDto actual = controller.rename(3, request);

        assertSame(expected, actual);
        verify(organizationService).rename(3, 7, "Renamed Org", "Original Org", 4L);
    }

    @Test
    void layoutDelegatesIndependentCursorsAndLimit() {
        OrganizationLayoutDto expected = new OrganizationLayoutDto(
            new OrganizationIdentityDto(3, "Org", "org", 4L, null),
            List.of(),
            null,
            List.of(),
            null);
        when(organizationService.getLayout(3, 7, 11, 13, 25)).thenReturn(expected);

        OrganizationLayoutDto actual = controller.layout(3, 11, 13, 25);

        assertSame(expected, actual);
        verify(organizationService).getLayout(3, 7, 11, 13, 25);
    }
}
