package ooo.klae.connex.backend.sso;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;

/** Provides deterministic DNS fixtures without changing the production resolver contract. */
public final class SsoHttpClientTestSupport {
    private SsoHttpClientTestSupport() {
    }

    /** Creates a transport whose DNS can rebind a previously public destination to loopback. */
    public static SsoHttpClient rebindingClient(SsoProperties properties, AtomicBoolean rebound) {
        return new SsoHttpClient(properties, host -> host.equals("localhost")
                ? new InetAddress[] { InetAddress.getByName(rebound.get() ? "127.0.0.1" : "93.184.216.34") }
                : InetAddress.getAllByName(host), Duration.ofSeconds(10));
    }

    /** Parks two callback stages at the HTTP exchange while keeping all transport admission real. */
    public static SsoHttpClient callbackClient(SsoProperties properties, CountDownLatch userInfoEntered,
            CountDownLatch tokenEntered, CountDownLatch release, AtomicInteger stalledRequests) throws Exception {
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.executeOpen(any(), any(), any())).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(1, ClassicHttpRequest.class);
            String host = request.getUri().getHost();
            if (host.equals("userinfo.a.example") || host.equals("token.a.example")) {
                stalledRequests.incrementAndGet();
                (host.equals("userinfo.a.example") ? userInfoEntered : tokenEntered).countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
            }
            String body = request.getMethod().equals("POST")
                    ? "{\"access_token\":\"test-access\",\"token_type\":\"Bearer\",\"expires_in\":60}"
                    : "{\"sub\":\"test-subject\"}";
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
            response.setHeader("Content-Type", "application/json");
            response.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
            return response;
        });
        SsoHttpClient http = spy(new SsoHttpClient(properties, host -> new InetAddress[] { publicAddress },
                Duration.ofSeconds(30)));
        doReturn(client).when(http).pinnedClient(anyString(), any(InetAddress[].class));
        return http;
    }

    /** Proves refusal before transport creation, including TLS attempts that an HTTP sentinel cannot count. */
    public static void verifyNoHttpClientCreated(SsoHttpClient http) {
        verify(http, never()).pinnedClient(anyString(), any(InetAddress[].class));
    }

    /** Supplies IPv6 transition addresses embedding private IPv4 destinations. */
    public static Stream<String> transitionHosts() {
        return Stream.of("64:ff9b::a00:1", "::ffff:10.0.0.1", "::a00:1", "2002:a00:1::",
                "2001:0:5db8:d822:0:0:f5ff:fffe", "2001:0:a00:1:0:0:a247:27dd",
                "64:ff9b:1:a00:0:100::", "2000::5efe:a00:1");
    }

    /** Preserves the IPv6 family for mapped-address DNS fixtures, which Java normally collapses. */
    public static InetAddress transitionAddress(String host) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(host);
        if (address.getAddress().length == 4) {
            byte[] mapped = new byte[16];
            mapped[10] = (byte) 0xff;
            mapped[11] = (byte) 0xff;
            System.arraycopy(address.getAddress(), 0, mapped, 12, 4);
            return Inet6Address.getByAddress(null, mapped, -1);
        }
        return address;
    }
}
