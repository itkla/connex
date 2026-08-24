package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProviderCapturePolicyServiceTest {

    @ParameterizedTest
    @CsvSource({
        "paused, connection_paused",
        "error, connection_error",
        "revoking, connection_disconnecting",
        "disconnecting, connection_disconnecting",
        "purge_failed, connection_purge_failed",
        "revoked, not_connected",
        "disconnected, not_connected",
        "future_internal_state, not_connected"
    })
    void connectionRestrictionsNeverExposeInternalLifecycleStatuses(
            String status, String expected) {
        assertEquals(
            expected,
            ProviderCapturePolicyService.connectionRestriction(status));
    }
}
