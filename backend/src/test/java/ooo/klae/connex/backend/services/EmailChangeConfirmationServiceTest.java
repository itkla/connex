package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.dto.RevokedInvitationDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;

@ExtendWith(MockitoExtension.class)
class EmailChangeConfirmationServiceTest {

    @Mock private OneTimeLinkFlowService oneTimeLinkFlowService;
    @Mock private EmailChangeService emailChangeService;
    @Mock private RevokedGrantNotificationCleanup cleanup;

    private EmailChangeConfirmationService service;
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final List<RevokedInvitationDto> revoked = List.of(new RevokedInvitationDto(4, 1, "Workspace"));

    @BeforeEach
    void setUp() {
        service = new EmailChangeConfirmationService(oneTimeLinkFlowService, emailChangeService, cleanup);
    }

    @Test
    void theBrowserFlowCleansUpAfterTheConfirmationReturns() {
        when(oneTimeLinkFlowService.consumeEmailChange(eq(request), eq("grant"), any()))
            .thenReturn(new EmailChangeConfirmation(41, revoked));

        assertEquals(revoked, service.confirmFromBrowserFlow(request, "grant"));

        InOrder order = inOrder(oneTimeLinkFlowService, cleanup);
        order.verify(oneTimeLinkFlowService).consumeEmailChange(eq(request), eq("grant"), any());
        order.verify(cleanup).cleanUp(41, revoked);
    }

    @Test
    void theProgrammaticEntryCleansUpAfterTheConfirmationReturns() {
        when(emailChangeService.confirmChange("raw")).thenReturn(new EmailChangeConfirmation(41, revoked));

        assertEquals(revoked, service.confirm("raw"));

        InOrder order = inOrder(emailChangeService, cleanup);
        order.verify(emailChangeService).confirmChange("raw");
        order.verify(cleanup).cleanUp(41, revoked);
    }

    @Test
    void aFailedConfirmationNeverStartsTheCleanup() {
        when(oneTimeLinkFlowService.consumeEmailChange(eq(request), eq("grant"), any()))
            .thenThrow(new BadRequestException("This verification link is invalid or has expired"));

        assertThrows(BadRequestException.class, () -> service.confirmFromBrowserFlow(request, "grant"));

        verifyNoInteractions(cleanup);
    }

    @Test
    void bothEntriesRefuseACallersTransactionBeforeConsumingAnything() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.confirmFromBrowserFlow(request, "grant"));
            assertThrows(IllegalStateException.class, () -> service.confirm("raw"));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(oneTimeLinkFlowService, emailChangeService, cleanup);
    }
}
