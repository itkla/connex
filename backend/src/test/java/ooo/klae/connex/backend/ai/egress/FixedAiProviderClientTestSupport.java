package ooo.klae.connex.backend.ai.egress;

import java.net.InetAddress;

import ooo.klae.connex.backend.ai.AiProperties;

/** Runs assistant callback tests through the real bounded transport against a local fixture. */
public final class FixedAiProviderClientTestSupport {
    private FixedAiProviderClientTestSupport() {
    }

    /** Creates the production transport with only DNS replaced by a deterministic loopback fixture. */
    public static FixedAiProviderClient loopback() {
        return new FixedAiProviderClient(new AiProperties(), host -> InetAddress.getLoopbackAddress());
    }

    /** Releases the transport's executors after the local fixture completes. */
    public static void close(FixedAiProviderClient client) {
        client.shutdown();
    }
}
