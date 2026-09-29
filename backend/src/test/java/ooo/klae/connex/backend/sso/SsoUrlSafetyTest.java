package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SsoUrlSafetyTest {
    private static final SsoUrlSafety.HostResolver SYSTEM = InetAddress::getAllByName;

    @ParameterizedTest
    @MethodSource("ooo.klae.connex.backend.sso.SsoHttpClientTestSupport#transitionHosts")
    void rejectsTransitionIssuersAndDnsAnswers(String host) throws Exception {
        assertThrows(UnknownHostException.class, () -> SsoUrlSafety.resolveFetchableHttpUrl(
                "https://[" + host + "]:18080/probe", false, SYSTEM));
        InetAddress address = SsoHttpClientTestSupport.transitionAddress(host);
        assertThrows(UnknownHostException.class, () -> SsoUrlSafety.resolveFetchableHttpUrl(
                "https://idp.example", false, ignored -> new InetAddress[] { address }));
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://127.0.0.1/probe", "https://[::1]/", "https://10.1.2.3/",
            "https://169.254.169.254/", "https://100.64.0.1/", "https://[fc00::1]/", "https://0.0.0.0/" })
    void rejectsPrivateDestinations(String url) {
        assertThrows(UnknownHostException.class, () -> SsoUrlSafety.resolveFetchableHttpUrl(url, false, SYSTEM));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ftp://93.184.216.34/", "//93.184.216.34/", "https://user@93.184.216.34/",
            "https://93.184.216.34:0/", "https://93.184.216.34/#fragment", "https://93.184.216.34:65536/" })
    void rejectsAmbiguousUrlsEvenWithPrivateHostsAllowed(String url) {
        assertThrows(UnknownHostException.class, () -> SsoUrlSafety.resolveFetchableHttpUrl(url, true, SYSTEM));
    }

    @Test
    void rejectsMixedDnsAnswersAndEmptyResolution() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        InetAddress privateAddress = InetAddress.getByName("127.0.0.1");
        assertThrows(UnknownHostException.class, () -> SsoUrlSafety.resolveFetchableHttpUrl(
                "https://idp.example", false, host -> new InetAddress[] { publicAddress, privateAddress }));
        assertThrows(UnknownHostException.class, () -> SsoUrlSafety.resolveFetchableHttpUrl(
                "https://idp.example", false, host -> new InetAddress[0]));
    }

    @Test
    void returnsEveryValidatedAddressSoTheTransportCanFailOver() throws Exception {
        InetAddress first = InetAddress.getByName("93.184.216.34");
        InetAddress second = InetAddress.getByName("93.184.216.35");
        InetAddress[] pinned = SsoUrlSafety.resolveFetchableHttpUrl("https://idp.example", false,
                host -> new InetAddress[] { first, second });
        assertArrayEquals(new InetAddress[] { first, second }, pinned);
    }

    @Test
    void cleartextPublicDestinationsRequireThePrivateIssuerExemptionBeforeDns() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        assertThrows(SsoUrlSafety.RefusedDestinationException.class, () -> SsoUrlSafety.resolveFetchableHttpUrl(
                "http://idp.example/token", false, host -> {
                    throw new AssertionError("Cleartext destination reached DNS");
                }));
        assertArrayEquals(new InetAddress[] { publicAddress }, SsoUrlSafety.resolveFetchableHttpUrl(
                "http://idp.example/token", true, host -> new InetAddress[] { publicAddress }));
    }

    @Test
    void trustedPrivateIdpRequiresExplicitOptIn() throws Exception {
        assertDoesNotThrow(() -> SsoUrlSafety.resolveFetchableHttpUrl("http://127.0.0.1", true, SYSTEM));
        assertDoesNotThrow(() -> SsoUrlSafety.resolveFetchableHttpUrl("https://93.184.216.34", false, SYSTEM));
    }

    @Test
    void surroundingWhitespaceIsTrimmedRatherThanRefused() {
        assertDoesNotThrow(() -> SsoUrlSafety.requireValidHttpUrl(" https://idp.example/tenant\n"));
        assertDoesNotThrow(() -> SsoUrlSafety.resolveFetchableHttpUrl(" https://93.184.216.34/\n", false, SYSTEM));
        assertTrue(
                SsoUrlSafety.isWellFormedHttpUrl("\thttps://idp.example "));
    }
}
