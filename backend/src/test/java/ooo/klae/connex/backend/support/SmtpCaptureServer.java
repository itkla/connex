package ooo.klae.connex.backend.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.mail.Session;
import jakarta.mail.Part;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;

/** Captures one bounded MIME message on loopback through an accepting or rejecting SMTP conversation. */
public final class SmtpCaptureServer implements AutoCloseable {
    private static final int MAX_MESSAGE_BYTES = 1_048_576;
    private final ServerSocket server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Future<byte[]> message;
    private final boolean rejectWithBearer;
    private volatile Socket connection;

    /** Opens an ephemeral loopback port with bounded accept, read, and capture time. */
    public SmtpCaptureServer() throws IOException {
        this(false);
    }

    /** Opens a relay that optionally echoes the captured bearer in a final 554 response. */
    public SmtpCaptureServer(boolean rejectWithBearer) throws IOException {
        this.rejectWithBearer = rejectWithBearer;
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        server.setSoTimeout(10_000);
        message = executor.submit(this::capture);
    }

    /** Returns the ephemeral SMTP port. */
    public int port() {
        return server.getLocalPort();
    }

    /** Waits at most ten seconds and decodes the captured SMTP DATA as MIME. */
    public MimeMessage awaitMessage() throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(message.get(10, TimeUnit.SECONDS)));
    }

    private byte[] capture() throws Exception {
        try (Socket socket = server.accept()) {
            connection = socket;
            socket.setSoTimeout(10_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            reply(output, "220 loopback ready");
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            boolean readingData = false;
            boolean captured = false;
            String line;
            while ((line = line(input)) != null) {
                if (readingData) {
                    if (".".equals(line)) {
                        captured = true;
                        readingData = false;
                        reply(output, rejectWithBearer
                                ? "554 rejected " + unsubscribeBearer(new MimeMessage(
                                        Session.getInstance(new Properties()),
                                        new ByteArrayInputStream(data.toByteArray())))
                                        + " diagnostic " + "C".repeat(64) + " retained"
                                : "250 accepted");
                    } else {
                        byte[] bytes = ((line.startsWith("..") ? line.substring(1) : line) + "\r\n")
                                .getBytes(StandardCharsets.ISO_8859_1);
                        if (data.size() + bytes.length > MAX_MESSAGE_BYTES) {
                            throw new IOException("SMTP capture exceeded message limit");
                        }
                        data.writeBytes(bytes);
                    }
                } else if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                    reply(output, "250 loopback");
                } else if (line.startsWith("MAIL FROM") || line.startsWith("RCPT TO")) {
                    reply(output, "250 accepted");
                } else if ("DATA".equals(line)) {
                    readingData = true;
                    reply(output, "354 end with dot");
                } else if ("QUIT".equals(line)) {
                    reply(output, "221 closing");
                    break;
                } else {
                    reply(output, "250 ok");
                }
            }
            if (!captured) {
                throw new IOException("SMTP capture did not receive a message");
            }
            return data.toByteArray();
        }
    }

    /** Extracts the fragment bearer from decoded MIME text, including multipart messages. */
    public static String unsubscribeBearer(Part part) throws Exception {
        if (part.isMimeType("text/*")) {
            Matcher token = Pattern.compile("/unsubscribe#token=([a-f0-9]{64})")
                    .matcher((String) part.getContent());
            return token.find() ? token.group(1) : null;
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                String bearer = unsubscribeBearer(multipart.getBodyPart(index));
                if (bearer != null) {
                    return bearer;
                }
            }
        }
        return null;
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) >= 0) {
            if (next == '\n') {
                return line.toString(StandardCharsets.ISO_8859_1).replaceFirst("\r$", "");
            }
            if (line.size() >= 8192) {
                throw new IOException("SMTP capture exceeded line limit");
            }
            line.write(next);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static void reply(OutputStream output, String response) throws IOException {
        output.write((response + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    /** Closes both sockets and joins the capture worker even after a failed assertion. */
    @Override
    public void close() throws Exception {
        try {
            server.close();
            Socket socket = connection;
            if (socket != null) {
                socket.close();
            }
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("SMTP capture worker did not stop");
            }
        }
    }
}
