package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.jupiter.api.Test;

class PinnedSocketFactoryTest {

    @Test
    void ignoresCallerHostnameAndConnectsToPinnedAddress() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            PinnedSocketFactory factory = new PinnedSocketFactory(loopback, server.getLocalPort());

            try (Socket client = factory.createSocket("does-not-resolve.invalid", 2525);
                    Socket accepted = server.accept()) {
                assertEquals(loopback, accepted.getLocalAddress());
                assertEquals(server.getLocalPort(), client.getPort());
            }
        }
    }

    @Test
    void abortImmediatelyClosesEveryPinnedSocket() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        PinnedSocketFactory factory = new PinnedSocketFactory(loopback, 2525);

        try (Socket client = factory.createSocket()) {
            factory.abort();

            assertTrue(client.isClosed());
        }
    }

}
