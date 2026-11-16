package ooo.klae.connex.backend.services;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.RevokedInvitationDto;

/**
 * Confirms a verified email change and then clears the notifications its revoked invitations left
 * in each workspace's tenant catalog (#1708). The confirmation runs in its own transaction, owned
 * by another bean, and the cleanup only after that transaction commits, so neither entry point
 * may be called from inside a caller's transaction.
 */
@Service
@RequiredArgsConstructor
public class EmailChangeConfirmationService {

    private final OneTimeLinkFlowService oneTimeLinkFlowService;
    private final EmailChangeService emailChangeService;
    private final RevokedGrantNotificationCleanup revokedGrantNotificationCleanup;

    /**
     * Confirms the browser flow's email change and cleans up after it.
     *
     * @param request the current browser request
     * @param grant the email-change flow cookie value
     * @return the pending invitations the change revoked
     * @throws IllegalStateException when called inside a transaction, before anything is consumed
     */
    public List<RevokedInvitationDto> confirmFromBrowserFlow(HttpServletRequest request, String grant) {
        requireNoTransaction();
        EmailChangeConfirmation confirmation = oneTimeLinkFlowService.consumeEmailChange(
            request, grant, emailChangeService::confirmChangeByHash);
        return cleanUpAfter(confirmation);
    }

    /**
     * Confirms an email change from its raw token, the programmatic entry, and cleans up after it.
     *
     * @param rawToken the unhashed token from the verification link
     * @return the pending invitations the change revoked
     * @throws IllegalStateException when called inside a transaction, before anything is consumed
     */
    public List<RevokedInvitationDto> confirm(String rawToken) {
        requireNoTransaction();
        return cleanUpAfter(emailChangeService.confirmChange(rawToken));
    }

    private List<RevokedInvitationDto> cleanUpAfter(EmailChangeConfirmation confirmation) {
        revokedGrantNotificationCleanup.cleanUp(confirmation.userId(), confirmation.revokedInvitations());
        return confirmation.revokedInvitations();
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Email-change confirmation must not run inside a caller's transaction:"
                    + " its notification cleanup runs after the confirmation commits");
        }
    }
}
