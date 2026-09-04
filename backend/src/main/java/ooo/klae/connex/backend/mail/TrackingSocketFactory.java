package ooo.klae.connex.backend.mail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.SocketFactory;

/** Tracks ordinary hostname-routed SMTP sockets so a hard deadline can close them immediately. */
final class TrackingSocketFactory extends SocketFactory {

    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean aborted = new AtomicBoolean();

    @Override
    public Socket createSocket() {
        return track(new TrackingSocket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(
            String host, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }

    @Override
    public Socket createSocket(
            InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
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
