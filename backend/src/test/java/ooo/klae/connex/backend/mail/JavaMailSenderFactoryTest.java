package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import ooo.klae.connex.backend.support.SmtpCaptureServer;

class JavaMailSenderFactoryTest {

    private static final long STALL_DEADLINE_MILLIS = 2_000L;
    private static final long RELAY_LATCH_SECONDS = 5L;
    private static final long STALL_MARGIN_MILLIS = 6_000L;

    @ParameterizedTest
    @CsvSource({"base64, false", "base64, true", "quoted-printable, false", "quoted-printable, true"})
    void protocolTraceSuppressesEncodedDataButKeepsCommandsAndReplies(String encoding, boolean deadline)
            throws Exception {
        String bearer = "0123456789abcdef".repeat(4);
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previousLevel = logger.getLevel();
        java.util.logging.Logger protocolLogger = java.util.logging.Logger.getLogger("org.eclipse.angus.mail.smtp.protocol");
        java.util.logging.Level previousProtocolLevel = protocolLogger.getLevel();
        boolean bridgeInstalled = SLF4JBridgeHandler.isInstalled();
        if (!bridgeInstalled) {
            SLF4JBridgeHandler.install();
        }
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        protocolLogger.setLevel(java.util.logging.Level.FINEST);
        try (SmtpCaptureServer smtp = new SmtpCaptureServer()) {
            ResolvedMailConfig config = new ResolvedMailConfig("127.0.0.1", smtp.port(), null, null,
                    "sender@example.test", "Capture", false, false, false, 2000, 2000, 2000, false);
            JavaMailSenderFactory factory = new JavaMailSenderFactory();
            JavaMailSenderFactory.DeadlineBoundSender bounded = deadline
                    ? factory.deadlineBoundForConfig(config, InetAddress.getLoopbackAddress(), false,
                            TimeUnit.SECONDS.toNanos(5)) : null;
            JavaMailSender sender = deadline ? bounded.sender() : factory.forConfig(config);
            MimeMessage message = message(sender, "<a href=\"https://example.test/unsubscribe#token="
                    + bearer + "\">Unsubscribe</a>\r\n.\r\n<p>PRIVATE_DATA_SENTINEL</p>");
            message.setHeader("Content-Transfer-Encoding", encoding);
            if (deadline) {
                bounded.connectAndSend(message, () -> {});
            } else {
                sender.send(message);
            }
            MimeMessage captured = smtp.awaitMessage();
            assertEquals(encoding, captured.getEncoding());
            assertEquals(bearer, SmtpCaptureServer.unsubscribeBearer(captured));
            String encoded = new String(captured.getRawInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertFalse(encoded.contains(bearer));
            if ("quoted-printable".equals(encoding)) {
                assertTrue(encoded.contains("=\r\n"));
                assertTrue(encoded.replace("=\r\n", "").contains(bearer));
            }
            String protocol = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.TRACE && event.getLoggerName().endsWith(".protocol"))
                    .map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(protocol.contains("DATA"));
            assertTrue(protocol.contains("354"));
            assertTrue(protocol.contains("250 accepted"));
            assertFalse(protocol.contains("Content-Transfer-Encoding"));
            assertFalse(protocol.contains("PRIVATE_DATA_SENTINEL"));
            for (String line : encoded.split("\r?\n")) {
                if (line.length() > 16) {
                    assertFalse(protocol.contains(line));
                }
            }
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(bearer)));
        } finally {
            logger.setLevel(previousLevel);
            protocolLogger.setLevel(previousProtocolLevel);
            if (!bridgeInstalled) {
                SLF4JBridgeHandler.uninstall();
            }
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void authenticatedConfigsAreNotCachedWithPlaintextPasswords() throws Exception {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();
        ResolvedMailConfig first = config("secret-one");
        ResolvedMailConfig second = config("secret-one");

        factory.forConfig(first);
        factory.forConfig(second);

        assertEquals(0, cacheSize(factory));
    }

    @Test
    void unauthenticatedConfigsAreCached() throws Exception {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();

        factory.forConfig(config(null));
        factory.forConfig(config(null));

        assertEquals(1, cacheSize(factory));
    }

    @Test
    void resolvedMailConfigToStringRedactsPassword() {
        String rendered = config("secret-one").toString();

        assertFalse(rendered.contains("secret-one"));
        assertFalse(rendered.contains("password=secret"));
    }

    @Test
    void pinnedConfigsUsePinnedSocketWithoutCaching() throws Exception {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();
        InetAddress address = InetAddress.getByName("203.0.113.10");

        JavaMailSenderImpl sender = assertInstanceOf(
            JavaMailSenderImpl.class, factory.forConfig(config(null), address));

        assertInstanceOf(PinnedSocketFactory.class,
            sender.getJavaMailProperties().get("mail.smtp.socketFactory"));
        assertEquals("false", sender.getJavaMailProperties().get("mail.smtp.socketFactory.fallback"));
        assertEquals(0, cacheSize(factory));
    }

    @Test
    void tlsTransportRequiresUpgradeAndChecksServerIdentity() {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();
        JavaMailSenderImpl sender = assertInstanceOf(JavaMailSenderImpl.class, factory.forConfig(config(null)));

        assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.starttls.required"));
        assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.ssl.checkserveridentity"));
        assertTrue(Boolean.parseBoolean(sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable")));
    }

    @Test
    void instanceDeadlineSenderPreservesHostnameAndBoundsEverySocketTimeout() {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();

        JavaMailSenderImpl sender = assertInstanceOf(
                JavaMailSenderImpl.class,
                factory.deadlineBoundForConfig(
                        config(null), InetAddress.getLoopbackAddress(), false,
                        250_000_000L).sender());

        assertEquals("smtp.example.com", sender.getHost());
        assertInstanceOf(TrackingSocketFactory.class,
                sender.getJavaMailProperties().get("mail.smtp.socketFactory"));
        assertEquals("false", sender.getJavaMailProperties().get("mail.smtp.socketFactory.fallback"));
        assertEquals("250", sender.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout"));
        assertEquals("250", sender.getJavaMailProperties().getProperty("mail.smtp.timeout"));
        assertNull(sender.getJavaMailProperties().getProperty("mail.smtp.writetimeout"));
    }

    @Test
    void deadlineBeforeTransportCreationFailsWithoutReturningAConnectableTransport() {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();
        JavaMailSenderFactory.DeadlineBoundSender deadlineBound =
                factory.deadlineBoundForConfig(
                        config(null), InetAddress.getLoopbackAddress(), false,
                        250_000_000L);
        JavaMailSenderImpl sender = assertInstanceOf(
                JavaMailSenderImpl.class, deadlineBound.sender());

        deadlineBound.abort().run();
        MessagingException failure = assertThrows(
                MessagingException.class, sender::testConnection);

        assertTrue(JavaMailSenderFactory.isDeadlineBeforeTransport(failure));
    }

    @Test
    void deadlineBoundFactoriesOpenPlainTrackedSocketsForEveryTlsMode() throws Exception {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();
        InetAddress address = InetAddress.getLoopbackAddress();

        for (ResolvedMailConfig config : new ResolvedMailConfig[] {implicitTlsConfig(), config(null)}) {
            for (boolean pinned : new boolean[] {false, true}) {
                JavaMailSenderFactory.DeadlineBoundSender deadlineBound =
                        factory.deadlineBoundForConfig(config, address, pinned, 250_000_000L);
                JavaMailSenderImpl sender = assertInstanceOf(
                        JavaMailSenderImpl.class, deadlineBound.sender());
                Object installed = sender.getJavaMailProperties().get("mail.smtp.socketFactory");
                if (pinned) {
                    assertInstanceOf(PinnedSocketFactory.class, installed);
                } else {
                    assertInstanceOf(TrackingSocketFactory.class, installed);
                }
                Socket socket = assertInstanceOf(SocketFactory.class, installed).createSocket();

                assertFalse(socket instanceof SSLSocket);
                assertNull(sender.getJavaMailProperties().get("mail.smtp.ssl.socketFactory"));
                assertNull(sender.getJavaMailProperties().get("mail.smtp.ssl.socketFactory.class"));
                assertEquals(
                        String.valueOf(config.ssl()),
                        sender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"));

                deadlineBound.abort().run();
                assertTrue(socket.isClosed());
            }
        }
    }

    @Test
    void implicitTlsCompletesOneAngusHandshakeAndSmtpConversation() throws Exception {
        SSLContext serverContext = TestTlsContexts.forServerName("localhost");

        for (boolean pinned : new boolean[] {false, true}) {
            AtomicInteger handshakes = new AtomicInteger();
            try (SSLServerSocket server = (SSLServerSocket) serverContext
                    .getServerSocketFactory().createServerSocket(
                            0, 1, InetAddress.getLoopbackAddress())) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<Boolean> relay = executor.submit(() -> {
                    try (SSLSocket socket = (SSLSocket) server.accept()) {
                        socket.startHandshake();
                        handshakes.incrementAndGet();
                        return runSmtpConversation(socket, true, null, null);
                    }
                });
                try {
                    JavaMailSenderFactory.DeadlineBoundSender deadlineBound =
                            new JavaMailSenderFactory().deadlineBoundForConfig(
                                    loopbackImplicitTlsConfig(server.getLocalPort()),
                                    InetAddress.getLoopbackAddress(),
                                    pinned,
                                    TimeUnit.SECONDS.toNanos(5));
                    trustLoopbackRelay(deadlineBound);
                    MimeMessage message = message(deadlineBound.sender(), "Body");
                    AtomicInteger submissionBoundaries = new AtomicInteger();

                    deadlineBound.connectAndSend(message, submissionBoundaries::incrementAndGet);

                    assertTrue(relay.get(RELAY_LATCH_SECONDS, TimeUnit.SECONDS));
                    assertEquals(1, handshakes.get());
                    assertEquals(1, submissionBoundaries.get());
                } finally {
                    server.close();
                    executor.shutdownNow();
                }
            }
        }
    }

    @Test
    void startTlsCompletesOneAngusUpgradeAndSmtpConversation() throws Exception {
        SSLContext serverContext = TestTlsContexts.forServerName("localhost");

        for (boolean pinned : new boolean[] {false, true}) {
            AtomicInteger handshakes = new AtomicInteger();
            try (ServerSocket server = new ServerSocket(
                    0, 1, InetAddress.getLoopbackAddress())) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<Boolean> relay = executor.submit(() -> runStartTlsRelay(
                        server, serverContext, handshakes));
                try {
                    JavaMailSenderFactory.DeadlineBoundSender deadlineBound =
                            new JavaMailSenderFactory().deadlineBoundForConfig(
                                    startTlsConfig(server.getLocalPort()),
                                    InetAddress.getLoopbackAddress(),
                                    pinned,
                                    TimeUnit.SECONDS.toNanos(5));
                    trustLoopbackRelay(deadlineBound);
                    MimeMessage message = message(deadlineBound.sender(), "Body");
                    AtomicInteger submissionBoundaries = new AtomicInteger();

                    deadlineBound.connectAndSend(message, submissionBoundaries::incrementAndGet);

                    assertTrue(relay.get(RELAY_LATCH_SECONDS, TimeUnit.SECONDS));
                    assertEquals(1, handshakes.get());
                    assertEquals(1, submissionBoundaries.get());
                } finally {
                    server.close();
                    executor.shutdownNow();
                }
            }
        }
    }

    @Test
    void implicitTlsRejectsARelayPresentingAnotherHostsCertificate() throws Exception {
        SSLContext serverContext = TestTlsContexts.forServerName("wrong.example.test");

        try (SSLServerSocket server = (SSLServerSocket) serverContext
                .getServerSocketFactory().createServerSocket(
                        0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.startHandshake();
                } catch (IOException exception) {
                    return null;
                }
                return null;
            });
            try {
                JavaMailSenderFactory.DeadlineBoundSender deadlineBound =
                        new JavaMailSenderFactory().deadlineBoundForConfig(
                                loopbackImplicitTlsConfig(server.getLocalPort()),
                                InetAddress.getLoopbackAddress(),
                                false,
                                TimeUnit.SECONDS.toNanos(5));
                trustLoopbackRelay(deadlineBound);
                MimeMessage message = message(deadlineBound.sender(), "Body");
                AtomicInteger submissionBoundaries = new AtomicInteger();

                assertThrows(
                        MessagingException.class,
                        () -> deadlineBound.connectAndSend(
                                message, submissionBoundaries::incrementAndGet));
                assertEquals(0, submissionBoundaries.get());
            } finally {
                server.close();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void deadlineAbortReleasesAnImplicitTlsSendParkedAfterData() throws Exception {
        SSLContext serverContext = TestTlsContexts.forServerName("localhost");

        for (boolean pinned : new boolean[] {false, true}) {
            CountDownLatch dataAccepted = new CountDownLatch(1);
            AtomicBoolean relayKeepsSocketOpen = new AtomicBoolean(true);
            try (SSLServerSocket server = (SSLServerSocket) serverContext
                    .getServerSocketFactory().createServerSocket(
                            0, 1, InetAddress.getLoopbackAddress())) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                ScheduledExecutorService deadlineExecutor =
                        Executors.newSingleThreadScheduledExecutor();
                executor.submit(() -> {
                    try (SSLSocket socket = (SSLSocket) server.accept()) {
                        socket.startHandshake();
                        return runSmtpConversation(socket, true, dataAccepted, relayKeepsSocketOpen);
                    }
                });
                try {
                    JavaMailSenderFactory.DeadlineBoundSender deadlineBound =
                            new JavaMailSenderFactory().deadlineBoundForConfig(
                                    loopbackImplicitTlsConfig(server.getLocalPort()),
                                    InetAddress.getLoopbackAddress(),
                                    pinned,
                                    TimeUnit.MILLISECONDS.toNanos(STALL_DEADLINE_MILLIS));
                    trustLoopbackRelay(deadlineBound);
                    MimeMessage message = message(
                            deadlineBound.sender(), "x".repeat(12 * 1024 * 1024));
                    AtomicInteger submissionBoundaries = new AtomicInteger();
                    long started = System.nanoTime();
                    deadlineExecutor.schedule(
                            deadlineBound.abort(), STALL_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);

                    assertThrows(
                            MessagingException.class,
                            () -> deadlineBound.connectAndSend(
                                    message, submissionBoundaries::incrementAndGet));

                    long elapsedNanos = System.nanoTime() - started;
                    assertTrue(dataAccepted.await(RELAY_LATCH_SECONDS, TimeUnit.SECONDS));
                    assertEquals(1, submissionBoundaries.get());
                    assertTrue(elapsedNanos < Duration.ofMillis(
                            STALL_DEADLINE_MILLIS + STALL_MARGIN_MILLIS).toNanos());
                    deadlineExecutor.shutdown();
                    assertTrue(deadlineExecutor.awaitTermination(
                            RELAY_LATCH_SECONDS, TimeUnit.SECONDS));
                } finally {
                    relayKeepsSocketOpen.set(false);
                    deadlineExecutor.shutdownNow();
                    server.close();
                    executor.shutdownNow();
                }
            }
        }
    }

    private static void trustLoopbackRelay(
            JavaMailSenderFactory.DeadlineBoundSender deadlineBound) {
        assertInstanceOf(JavaMailSenderImpl.class, deadlineBound.sender())
                .getJavaMailProperties().put("mail.smtp.ssl.trust", "localhost");
    }

    private static ResolvedMailConfig config(String password) {
        return new ResolvedMailConfig("smtp.example.com", 587, "user", password,
                "no-reply@example.com", "Connex", true, false, password != null,
                1000, 1000, 1000, false);
    }

    private static ResolvedMailConfig implicitTlsConfig() {
        return new ResolvedMailConfig("smtp.example.com", 465, "user", null,
                "no-reply@example.com", "Connex", false, true, false,
                1000, 1000, 1000, false);
    }

    private static ResolvedMailConfig loopbackImplicitTlsConfig(int port) {
        return new ResolvedMailConfig("localhost", port, null, null,
                "no-reply@example.com", "Connex", false, true, false,
                5000, 5000, 5000, false);
    }

    private static ResolvedMailConfig startTlsConfig(int port) {
        return new ResolvedMailConfig("localhost", port, null, null,
                "no-reply@example.com", "Connex", true, false, false,
                5000, 5000, 5000, false);
    }

    private static MimeMessage message(JavaMailSender sender, String body) throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        message.setFrom(new InternetAddress("sender@example.test"));
        message.setRecipient(
                Message.RecipientType.TO,
                new InternetAddress("recipient@example.test"));
        message.setSubject("TLS path");
        message.setText(body);
        return message;
    }

    private static boolean runStartTlsRelay(
            ServerSocket server,
            SSLContext serverContext,
            AtomicInteger handshakes) throws Exception {
        Socket accepted = server.accept();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                accepted.getInputStream(), StandardCharsets.US_ASCII));
        Writer writer = new OutputStreamWriter(
                accepted.getOutputStream(), StandardCharsets.US_ASCII);
        writeLine(writer, "220 loopback ready");
        String greeting = reader.readLine();
        if (greeting == null || !greeting.startsWith("EHLO")) {
            accepted.close();
            return false;
        }
        writeLine(writer, "250-loopback");
        writeLine(writer, "250 STARTTLS");
        if (!"STARTTLS".equals(reader.readLine())) {
            accepted.close();
            return false;
        }
        writeLine(writer, "220 begin TLS");
        try (SSLSocket socket = (SSLSocket) serverContext.getSocketFactory()
                .createSocket(accepted, "localhost", server.getLocalPort(), true)) {
            socket.setUseClientMode(false);
            socket.startHandshake();
            handshakes.incrementAndGet();
            return runSmtpConversation(socket, false, null, null);
        }
    }

    private static boolean runSmtpConversation(
            Socket socket, boolean greet, CountDownLatch dataAccepted,
            AtomicBoolean keepSocketOpen)
            throws IOException, InterruptedException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));
        Writer writer = new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.US_ASCII);
        if (greet) {
            writeLine(writer, "220 loopback ready");
        }
        String line = reader.readLine();
        if (line == null || !line.startsWith("EHLO")) {
            return false;
        }
        writeLine(writer, "250 loopback");
        boolean readingData = false;
        boolean accepted = false;
        while ((line = reader.readLine()) != null) {
            if (readingData) {
                if (".".equals(line)) {
                    accepted = true;
                    readingData = false;
                    writeLine(writer, "250 accepted");
                }
            } else if (line.startsWith("MAIL FROM") || line.startsWith("RCPT TO")) {
                writeLine(writer, "250 accepted");
            } else if ("DATA".equals(line)) {
                writeLine(writer, "354 end with dot");
                if (keepSocketOpen != null) {
                    dataAccepted.countDown();
                    while (keepSocketOpen.get()) {
                        Thread.sleep(50);
                    }
                    return false;
                }
                readingData = true;
            } else if ("QUIT".equals(line)) {
                writeLine(writer, "221 closing");
                return accepted;
            }
        }
        return accepted;
    }

    private static void writeLine(Writer writer, String line) throws IOException {
        writer.write(line + "\r\n");
        writer.flush();
    }

    private static int cacheSize(JavaMailSenderFactory factory) throws Exception {
        Field field = JavaMailSenderFactory.class.getDeclaredField("cache");
        field.setAccessible(true);
        ConcurrentHashMap<?, ?> cache = (ConcurrentHashMap<?, ?>) field.get(factory);
        return cache.size();
    }
}
