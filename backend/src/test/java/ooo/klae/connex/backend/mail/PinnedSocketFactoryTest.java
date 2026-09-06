package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import javax.net.ssl.SSLSocket;

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
    void keepsThePinOnAPlainSocketTheMailLibraryCanUpgradeToTls() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            PinnedSocketFactory factory = new PinnedSocketFactory(loopback, server.getLocalPort());

            try (Socket client = factory.createSocket();
                    Socket accepted = acceptAfterConnect(
                            server,
                            client,
                            InetSocketAddress.createUnresolved("does-not-resolve.invalid", 2525))) {
                assertFalse(client instanceof SSLSocket);
                assertEquals(server.getLocalPort(), client.getPort());
                assertEquals(loopback, accepted.getLocalAddress());
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

    @Test
    void abortClosesASocketThatIsAlreadyStreamingToTheRelay() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            PinnedSocketFactory factory = new PinnedSocketFactory(loopback, server.getLocalPort());

            try (Socket client = factory.createSocket("does-not-resolve.invalid", 2525);
                    Socket accepted = server.accept()) {
                assertFalse(client.isClosed());

                factory.abort();

                assertTrue(client.isClosed());
            }
        }
    }

    private static Socket acceptAfterConnect(
            ServerSocket server, Socket client, InetSocketAddress requestedDestination)
            throws Exception {
        client.connect(requestedDestination);
        return server.accept();
    }
}
