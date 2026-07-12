package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AppiIncidentDto;
import ooo.klae.connex.backend.dto.AppiIncidentRequest;
import ooo.klae.connex.backend.dto.AppiIncidentScopeDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.services.AppiIncidentService;
import ooo.klae.connex.backend.services.AuthService;

@ExtendWith(MockitoExtension.class)
class AppiIncidentControllerTest {
    @Mock private AppiIncidentService appiIncidentService;
    @Mock private AuthService authService;

    private AppiIncidentController controller;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new AppiIncidentController(appiIncidentService, authService);
        user = new User();
        user.setId(7);
        when(authService.getCurrentUser()).thenReturn(user);
    }

    @Test
    void listForwardsOrgActorAndPaging() {
        when(appiIncidentService.list(3, 7, 25, 50)).thenReturn(List.of());
        controller.list(3, 25, 50);
        verify(appiIncidentService).list(3, 7, 25, 50);
    }

    @Test
    void createForwardsOrgActorAndBody() {
        AppiIncidentRequest request = new AppiIncidentRequest();
        when(appiIncidentService.create(3, 7, request)).thenReturn(new AppiIncidentDto());
        controller.create(3, request);
        verify(appiIncidentService).create(3, 7, request);
    }

    @Test
    void getUpdateAndScopeForwardOrgActorAndIncident() {
        AppiIncidentRequest request = new AppiIncidentRequest();
        when(appiIncidentService.get(3, 9L, 7)).thenReturn(new AppiIncidentDto());
        when(appiIncidentService.update(3, 9L, 7, request)).thenReturn(new AppiIncidentDto());
        when(appiIncidentService.scope(3, 9L, 7, 2, 25))
            .thenReturn(new PageResponse<>(List.of(new AppiIncidentScopeDto()), 1));

        controller.get(3, 9L);
        controller.update(3, 9L, request);
        controller.scope(3, 9L, 2, 25);

        verify(appiIncidentService).get(3, 9L, 7);
        verify(appiIncidentService).update(3, 9L, 7, request);
        verify(appiIncidentService).scope(3, 9L, 7, 2, 25);
    }
}
