package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ProviderConnection;

class ProviderConnectionDtoTest {

    @Test
    void internalLifecycleStatesNeverReachClients() {
        ProviderConnection connection = new ProviderConnection();
        connection.setStatus("revoking");
        assertEquals(
            "disconnecting",
            ProviderConnectionDto.from(connection).status());

        connection.setStatus("disconnected");
        assertEquals("revoked", ProviderConnectionDto.from(connection).status());
    }
}
