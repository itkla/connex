package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.bedrock.BedrockRegion;

class AiEgressGuardTest {

    @Test
    void requireFetchable_rejectsNonAllowlistedHostsBeforeResolution() {
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requireFetchable("bedrock-runtime.us-east-1.amazonaws.com.evil.com"));
        assertThrows(AiProviderException.class, () -> AiEgressGuard.requireFetchable("evil.com"));
        assertThrows(AiProviderException.class, () -> AiEgressGuard.requireFetchable("203.0.113.10"));
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requireFetchable("bedrock-runtime.mars-1.amazonaws.com"));
    }

    @Test
    void requirePublicAddresses_allowsPublicResolvedAddresses() throws Exception {
        InetAddress[] addresses = {
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("2606:4700:4700::1111")
        };

        assertDoesNotThrow(() -> AiEgressGuard.requirePublicAddresses(BedrockRegion.US_EAST_1.host(), addresses));
    }

    @Test
    void requirePublicAddresses_rejectsBlockedAddressFamilies() throws Exception {
        assertBlocked("127.0.0.1");
        assertBlocked("10.0.0.1");
        assertBlocked("172.16.0.1");
        assertBlocked("192.168.0.1");
        assertBlocked("100.64.0.1");
        assertBlocked("100.127.255.255");
        assertBlocked("169.254.169.254");
        assertBlocked("224.0.0.1");
        assertBlocked("0.0.0.0");
        assertBlocked("::1");
        assertBlocked("fc00::1");
        assertBlocked("fd00::1");
    }

    @Test
    void requirePublicAddresses_rejectsEmptyResolution() {
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requirePublicAddresses(BedrockRegion.US_EAST_1.host(), new InetAddress[0]));
    }

    private static void assertBlocked(String address) throws Exception {
        InetAddress resolved = InetAddress.getByName(address);
        assertThrows(AiProviderException.class,
                () -> AiEgressGuard.requirePublicAddresses(BedrockRegion.US_EAST_1.host(),
                        new InetAddress[] { resolved }));
    }
}
