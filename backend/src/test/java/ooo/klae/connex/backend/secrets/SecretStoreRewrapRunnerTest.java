package ooo.klae.connex.backend.secrets;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SecretStoreRewrapRunnerTest {

    @Test
    void skipsBatchRewrapWhenDisabled() {
        SecretStore secretStore = Mockito.mock(SecretStore.class);
        SecretStoreProperties properties = new SecretStoreProperties();
        SecretStoreRewrapRunner runner = new SecretStoreRewrapRunner(secretStore, properties);

        runner.run(null);

        verify(secretStore, never()).rewrapBatchToActiveKey(Mockito.anyInt());
    }

    @Test
    void runsBatchRewrapWithConfiguredLimitWhenEnabled() {
        SecretStore secretStore = Mockito.mock(SecretStore.class);
        SecretStoreProperties properties = new SecretStoreProperties();
        properties.setBatchRewrapOnStartup(true);
        properties.setBatchRewrapLimit(25);
        when(secretStore.rewrapBatchToActiveKey(25)).thenReturn(3);
        when(secretStore.activeKeyId()).thenReturn("prod-v2");
        SecretStoreRewrapRunner runner = new SecretStoreRewrapRunner(secretStore, properties);

        runner.run(null);

        verify(secretStore).rewrapBatchToActiveKey(25);
    }
}
