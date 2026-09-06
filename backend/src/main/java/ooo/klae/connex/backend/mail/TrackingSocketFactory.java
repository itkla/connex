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
 * Opens SMTP sockets to a pre-resolved address that no workspace destination policy approved — an
 * explicitly allowed internal relay or a trusted instance default — and keeps them reachable so a
 * hard deadline can close them immediately.
 *
 * <p>Like {@link PinnedSocketFactory} the sockets are always plain TCP sockets so that the deadline
 * abort closes the raw socket underneath any TLS layer the mail library stacks on top of it.
 */
final class TrackingSocketFactory extends SocketFactory {

    private final InetAddress address;
    private final int port;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean aborted = new AtomicBoolean();

    TrackingSocketFactory(InetAddress address, int port) {
        this.address = Objects.requireNonNull(address, "address");
        this.port = port;
    }

    @Override
    public Socket createSocket() {
        return track(new TrackingSocket(address, port));
    }

    @Override
    public Socket createSocket(String host, int ignoredPort) throws IOException {
        return connect();
    }

    @Override
    public Socket createSocket(
            String host, int ignoredPort, InetAddress localAddress, int localPort) throws IOException {
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
    public Socket createSocket(
            InetAddress ignoredAddress, int ignoredPort, InetAddress localAddress, int localPort)
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
        sockets.forEach(TrackingSocketFactory::close);
    }

    private Socket track(Socket socket) {
        sockets.add(socket);
        if (aborted.get()) {
            close(socket);
        }
        return socket;
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            return;
        }
    }

    private final class TrackingSocket extends Socket {

        private final SocketAddress destination;

        private TrackingSocket(InetAddress address, int port) {
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
