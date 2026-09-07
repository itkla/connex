package ooo.klae.connex.backend.mail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.SocketFactory;

/**
 * Opens SMTP sockets only to the address resolved and approved by {@link SmtpDestinationGuard}, and
 * keeps every socket it opened reachable so an absolute deadline can close it immediately.
 *
 * <p>The sockets are always plain TCP sockets, including for TLS transports: the mail library
 * layers TLS over the connected socket itself. Keeping the raw socket is what makes the deadline
 * abort effective, because closing the raw socket fails a write parked inside the TLS record layer
 * while closing the TLS layer would first have to take the record lock that parked write holds.
 */
final class PinnedSocketFactory extends SocketFactory {

    private final InetAddress address;
    private final int port;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean aborted = new AtomicBoolean();

    PinnedSocketFactory(InetAddress address, int port) {
        this.address = Objects.requireNonNull(address, "address");
        this.port = port;
    }

    @Override
    public Socket createSocket() {
        Socket socket = new PinnedSocket(address, port);
        sockets.add(socket);
        if (aborted.get()) {
            close(socket);
        }
        return socket;
    }

    @Override
    public Socket createSocket(String host, int ignoredPort) throws IOException {
        return connect();
    }

    @Override
    public Socket createSocket(String host, int ignoredPort, InetAddress localAddress, int localPort)
            throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress ignoredAddress, int ignoredPort) throws IOException {
        return connect();
    }

    @Override
    public Socket createSocket(InetAddress ignoredAddress, int ignoredPort, InetAddress localAddress, int localPort)
            throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }

    private Socket connect() throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }

    void abort() {
        aborted.set(true);
        sockets.forEach(PinnedSocketFactory::close);
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            return;
        }
    }

    private final class PinnedSocket extends Socket {

        private final SocketAddress destination;

        private PinnedSocket(InetAddress address, int port) {
            destination = new InetSocketAddress(address, port);
        }

        @Override
        public void connect(SocketAddress ignoredEndpoint) throws IOException {
            super.connect(destination);
        }

        @Override
        public void connect(SocketAddress ignoredEndpoint, int timeout) throws IOException {
            super.connect(destination, timeout);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                sockets.remove(this);
            }
        }
    }
}
