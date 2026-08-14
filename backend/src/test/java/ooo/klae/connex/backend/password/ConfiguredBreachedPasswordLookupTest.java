package ooo.klae.connex.backend.password;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ConfiguredBreachedPasswordLookupTest {
    private static final String SHA1 = "C805A2FFAF2B30CC484C8D610DFCC5292C1794DE";

    @Test
    void offlineSourceNeverInvokesRemoteClient() {
        BreachedPasswordProperties properties = properties("OFFLINE");
        HibpBreachedPasswordLookup remote = mock(HibpBreachedPasswordLookup.class);
        OfflineBreachedPasswordLookup offline = mock(OfflineBreachedPasswordLookup.class);
        when(offline.isBreached(SHA1)).thenReturn(true);
        ConfiguredBreachedPasswordLookup lookup = new ConfiguredBreachedPasswordLookup(
                properties, remote, offline);

        assertTrue(lookup.isBreached(SHA1));

        verify(remote, never()).isBreached(SHA1);
        verify(offline).isBreached(SHA1);
    }

    @Test
    void remoteSourceNeverInvokesOfflineLookup() {
        BreachedPasswordProperties properties = properties("REMOTE");
        HibpBreachedPasswordLookup remote = mock(HibpBreachedPasswordLookup.class);
        OfflineBreachedPasswordLookup offline = mock(OfflineBreachedPasswordLookup.class);
        when(remote.isBreached(SHA1)).thenReturn(false);
        ConfiguredBreachedPasswordLookup lookup = new ConfiguredBreachedPasswordLookup(
                properties, remote, offline);

        assertFalse(lookup.isBreached(SHA1));

        verify(remote).isBreached(SHA1);
        verify(offline, never()).isBreached(SHA1);
    }

    private static BreachedPasswordProperties properties(String source) {
        BreachedPasswordProperties properties = new BreachedPasswordProperties();
        properties.setSource(source);
        return properties;
    }
}
