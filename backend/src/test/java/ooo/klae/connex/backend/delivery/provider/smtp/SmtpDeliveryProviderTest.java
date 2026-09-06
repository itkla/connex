package ooo.klae.connex.backend.delivery.provider.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.DispatchStatus;
import ooo.klae.connex.backend.delivery.RenderedMessage;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.mail.JavaMailSenderFactory;
import ooo.klae.connex.backend.mail.JavaMailSenderFactory.DeadlineBoundSender;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mail.SmtpDestinationGuard;
import ooo.klae.connex.backend.mail.TestTlsContexts;

class SmtpDeliveryProviderTest {

    private static final long RELAY_LATCH_SECONDS = 5L;

    @Test
    void declaresSubmissionReplayAsNonIdempotent() {
        SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                mock(JavaMailSenderFactory.class),
                mock(SmtpDestinationGuard.class));
        try {
            assertFalse(provider.capabilities().idempotentSubmission());
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void dispatchTransmitsStableIdempotencyAndMessageIdHeaders() throws Exception {
        JavaMailSenderFactory senderFactory = mock(JavaMailSenderFactory.class);
        SmtpDestinationGuard destinationGuard = mock(SmtpDestinationGuard.class);
        JavaMailSender sender = mock(JavaMailSender.class);
        ResolvedMailConfig config = config(true);
        InetAddress address = InetAddress.getLoopbackAddress();
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        when(destinationGuard.resolveForSend(config)).thenReturn(address);
        when(senderFactory.deadlineBoundForConfig(eq(config), eq(address), eq(true), anyLong()))
                .thenReturn(deadlineBound(sender, () -> { }));
        when(sender.createMimeMessage()).thenReturn(mime);
        SmtpDeliveryProvider provider =
                new SmtpDeliveryProvider(senderFactory, destinationGuard);
        try {
            DispatchReceipt receipt = provider.dispatch(target(config), request(
                    System.nanoTime() + Duration.ofSeconds(5).toNanos()));

            assertEquals(DispatchStatus.SENT, receipt.status());
            assertEquals("send:1:2", mime.getHeader("Idempotency-Key", null));
            assertNotNull(mime.getHeader("Message-ID", null));
            verify(sender).send(mime);
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void expiredDeadlineMakesNoProviderCall() {
        JavaMailSenderFactory senderFactory = mock(JavaMailSenderFactory.class);
        SmtpDestinationGuard destinationGuard = mock(SmtpDestinationGuard.class);
        SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                senderFactory, destinationGuard, () -> 100L);
        try {
            DispatchReceipt receipt = provider.dispatch(target(config(false)), request(99L));

            assertEquals(DispatchStatus.REJECTED, receipt.status());
            verify(destinationGuard, never()).resolveForSend(config(false));
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void instanceDefaultUsesPreResolvedAddressAndHardDeadlineCancellation() throws Exception {
        JavaMailSenderFactory senderFactory = mock(JavaMailSenderFactory.class);
        SmtpDestinationGuard destinationGuard = mock(SmtpDestinationGuard.class);
        JavaMailSender sender = mock(JavaMailSender.class);
        ResolvedMailConfig config = config(false);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        CountDownLatch abortCalled = new CountDownLatch(1);
        InetAddress address = InetAddress.getLoopbackAddress();
        when(senderFactory.deadlineBoundForConfig(eq(config), eq(address), eq(false), anyLong()))
                .thenReturn(deadlineBound(sender, abortCalled::countDown));
        when(sender.createMimeMessage()).thenReturn(mime);
        doAnswer(invocation -> {
            abortCalled.await(5, TimeUnit.SECONDS);
            return null;
        }).when(sender).send(mime);
        SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                senderFactory, destinationGuard, System::nanoTime, hostname -> address);
        try {
            DispatchReceipt receipt = provider.dispatch(target(config), request(
                    System.nanoTime() + Duration.ofMillis(500).toNanos()));

            assertEquals(0, abortCalled.getCount());
            assertEquals(DispatchStatus.AMBIGUOUS, receipt.status());
            verify(destinationGuard, never()).resolveForSend(config);
            verify(senderFactory).deadlineBoundForConfig(
                    eq(config), eq(address), eq(false), anyLong());
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void instanceDnsResolutionCannotOutliveProviderDeadline() {
        JavaMailSenderFactory senderFactory = mock(JavaMailSenderFactory.class);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                senderFactory,
                mock(SmtpDestinationGuard.class),
                System::nanoTime,
                hostname -> {
                    releaseResolver.await();
                    return InetAddress.getLoopbackAddress();
                });
        long started = System.nanoTime();
        try {
            DispatchReceipt receipt = provider.dispatch(
                    target(config(false)),
                    request(started + Duration.ofMillis(200).toNanos()));

            assertEquals(DispatchStatus.REJECTED, receipt.status());
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos());
            verifyNoInteractions(senderFactory);
        } finally {
            releaseResolver.countDown();
            provider.shutdown();
        }
    }

    @Test
    void instanceSocketIsClosedAtDeadlineWhileRelaySlowDripsAfterData() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        CountDownLatch dataAccepted = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            ExecutorService relayExecutor = Executors.newSingleThreadExecutor();
            Future<?> relay = relayExecutor.submit(() -> runSlowDripRelay(server, dataAccepted));
            ResolvedMailConfig config = new ResolvedMailConfig(
                    loopback.getHostName(), server.getLocalPort(), null, null,
                    "no-reply@sender.test", "Sender", false, false, false,
                    5000, 5000, 5000, false);
            SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                    new JavaMailSenderFactory(), mock(SmtpDestinationGuard.class));
            long started = System.nanoTime();
            try {
                DispatchReceipt receipt = provider.dispatch(
                        target(config),
                        request(started + Duration.ofMillis(2000).toNanos()));

                assertTrue(dataAccepted.await(5, TimeUnit.SECONDS));
                assertEquals(DispatchStatus.AMBIGUOUS, receipt.status());
                assertTrue(System.nanoTime() - started < Duration.ofSeconds(8).toNanos());
            } finally {
                provider.shutdown();
                server.close();
                relay.get(3, TimeUnit.SECONDS);
                relayExecutor.shutdownNow();
            }
        }
    }

    @Test
    void workspaceInternalRelayUsesTrackedUnpinnedPathWhenExplicitlyAllowed() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        MailProperties mailProperties = new MailProperties();
        mailProperties.setAllowInternalHosts(true);
        JavaMailSenderFactory senderFactory = spy(new JavaMailSenderFactory());
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            ExecutorService relayExecutor = Executors.newSingleThreadExecutor();
            Future<Boolean> relay = relayExecutor.submit(() -> runAcceptingRelay(server));
            ResolvedMailConfig config = plainConfig(
                    "localhost", server.getLocalPort(), true);
            SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                    senderFactory,
                    new SmtpDestinationGuard(mailProperties),
                    System::nanoTime,
                    hostname -> loopback);
            try {
                DispatchReceipt receipt = provider.dispatch(
                        target(config),
                        request(System.nanoTime() + Duration.ofSeconds(5).toNanos()));

                assertEquals(DispatchStatus.SENT, receipt.status());
                assertTrue(relay.get(5, TimeUnit.SECONDS));
                verify(senderFactory).deadlineBoundForConfig(
                        eq(config), eq(loopback), eq(false), anyLong());
            } finally {
                provider.shutdown();
                server.close();
                relayExecutor.shutdownNow();
            }
        }
    }

    @Test
    void workspaceInternalRelayIsRefusedBeforeEgressWhenNotAllowed() {
        JavaMailSenderFactory senderFactory = mock(JavaMailSenderFactory.class);
        ResolvedMailConfig config = plainConfig("127.0.0.1", 587, true);
        SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                senderFactory,
                new SmtpDestinationGuard(new MailProperties()),
                System::nanoTime,
                hostname -> InetAddress.getLoopbackAddress());
        try {
            DispatchReceipt receipt = provider.dispatch(
                    target(config),
                    request(System.nanoTime() + Duration.ofSeconds(5).toNanos()));

            assertDefinitivelyNotSent(receipt);
            verifyNoInteractions(senderFactory);
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void unexpectedNullGuardResultFailsClosedBeforeUnpinnedResolution() {
        JavaMailSenderFactory senderFactory = mock(JavaMailSenderFactory.class);
        SmtpDestinationGuard destinationGuard = mock(SmtpDestinationGuard.class);
        ResolvedMailConfig config = plainConfig("relay.example.test", 587, true);
        SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                senderFactory,
                destinationGuard,
                System::nanoTime,
                hostname -> {
                    throw new AssertionError("Unexpected unpinned resolution");
                });
        try {
            DispatchReceipt receipt = provider.dispatch(
                    target(config),
                    request(System.nanoTime() + Duration.ofSeconds(5).toNanos()));

            assertDefinitivelyNotSent(receipt);
            verify(destinationGuard).resolveForSend(config);
            verify(destinationGuard).allowsInternalHosts();
            verifyNoInteractions(senderFactory);
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void trustedInstanceDefaultIsAdmittedWhileWorkspaceResolutionSaturatesTheResolver()
            throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        CountDownLatch saturated = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        SmtpDestinationGuard destinationGuard = mock(SmtpDestinationGuard.class);
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            ResolvedMailConfig workspaceConfig = plainConfig("blocked.test", server.getLocalPort(), true);
            ResolvedMailConfig instanceConfig = plainConfig("localhost", server.getLocalPort(), false);
            when(destinationGuard.resolveForSend(workspaceConfig)).thenAnswer(invocation -> {
                saturated.countDown();
                release.await(RELAY_LATCH_SECONDS, TimeUnit.SECONDS);
                return loopback;
            });
            ExecutorService relayExecutor = Executors.newSingleThreadExecutor();
            Future<Boolean> relay = relayExecutor.submit(() -> runAcceptingRelay(server));
            ExecutorService workspaceExecutor = Executors.newFixedThreadPool(2);
            SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                    new JavaMailSenderFactory(),
                    destinationGuard,
                    System::nanoTime,
                    hostname -> loopback);
            try {
                for (int held = 0; held < 2; held++) {
                    workspaceExecutor.submit(() -> provider.dispatch(
                            target(workspaceConfig),
                            request(System.nanoTime() + Duration.ofSeconds(4).toNanos())));
                }
                assertTrue(saturated.await(RELAY_LATCH_SECONDS, TimeUnit.SECONDS));

                DispatchReceipt rejectedWorkspaceSend = provider.dispatch(
                        target(workspaceConfig),
                        request(System.nanoTime() + Duration.ofSeconds(2).toNanos()));
                DispatchReceipt instanceSend = provider.dispatch(
                        target(instanceConfig),
                        request(System.nanoTime() + Duration.ofSeconds(2).toNanos()));

                assertEquals(DispatchStatus.REJECTED, rejectedWorkspaceSend.status());
                assertEquals(DispatchStatus.SENT, instanceSend.status());
                assertTrue(relay.get(RELAY_LATCH_SECONDS, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                provider.shutdown();
                server.close();
                workspaceExecutor.shutdownNow();
                relayExecutor.shutdownNow();
            }
        }
    }

    @Test
    void untrustedRelayCertificateIsNotSentWithoutAReconciliationOutcome() throws Exception {
        SSLContext serverContext = TestTlsContexts.forServerName("wrong.example.test");
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (SSLServerSocket server = (SSLServerSocket) serverContext
                .getServerSocketFactory().createServerSocket(0, 1, loopback)) {
            ExecutorService relayExecutor = Executors.newSingleThreadExecutor();
            Future<?> relay = relayExecutor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.startHandshake();
                } catch (IOException exception) {
                    return;
                }
            });
            ResolvedMailConfig config = implicitTlsConfig(
                    "localhost", server.getLocalPort(), false);
            SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                    new JavaMailSenderFactory(),
                    mock(SmtpDestinationGuard.class),
                    System::nanoTime,
                    hostname -> loopback);
            try {
                DispatchReceipt receipt = provider.dispatch(
                        target(config),
                        request(System.nanoTime() + Duration.ofSeconds(5).toNanos()));

                assertDefinitivelyNotSent(receipt);
                relay.get(5, TimeUnit.SECONDS);
            } finally {
                provider.shutdown();
                server.close();
                relayExecutor.shutdownNow();
            }
        }
    }

    @Test
    void connectionRefusalIsNotSentWithoutAReconciliationOutcome() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        int closedPort;
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            closedPort = server.getLocalPort();
        }
        ResolvedMailConfig config = plainConfig("localhost", closedPort, false);
        SmtpDeliveryProvider provider = new SmtpDeliveryProvider(
                new JavaMailSenderFactory(),
                mock(SmtpDestinationGuard.class),
                System::nanoTime,
                hostname -> loopback);
        try {
            DispatchReceipt receipt = provider.dispatch(
                    target(config),
                    request(System.nanoTime() + Duration.ofSeconds(5).toNanos()));

            assertDefinitivelyNotSent(receipt);
        } finally {
            provider.shutdown();
        }
    }

    private static DeliveryRequest request(long deadlineNanos) {
        return new DeliveryRequest(
                DeliveryChannel.EMAIL,
                "recipient@dest.test",
                new RenderedMessage("Subject", "<p>Body</p>", "Body"),
                42,
                "send:1:2",
                deadlineNanos);
    }

    private static ResolvedDeliveryProvider target(ResolvedMailConfig config) {
        return new ResolvedDeliveryProvider(
                SmtpDeliveryProvider.PROVIDER_ID,
                DeliveryChannel.EMAIL,
                7,
                null,
                "no-reply@sender.test",
                "Sender",
                DeliveryCredentials.of(Map.of()),
                false,
                "a".repeat(64),
                config);
    }

    private static ResolvedMailConfig config(boolean workspaceSupplied) {
        return new ResolvedMailConfig(
                "smtp.example.com", 587, null, null,
                "no-reply@sender.test", "Sender", true, false, false,
                1000, 1000, 1000, workspaceSupplied);
    }

    private static ResolvedMailConfig plainConfig(
            String host, int port, boolean workspaceSupplied) {
        return new ResolvedMailConfig(
                host, port, null, null,
                "no-reply@sender.test", "Sender", false, false, false,
                5000, 5000, 5000, workspaceSupplied);
    }

    private static ResolvedMailConfig implicitTlsConfig(
            String host, int port, boolean workspaceSupplied) {
        return new ResolvedMailConfig(
                host, port, null, null,
                "no-reply@sender.test", "Sender", false, true, false,
                5000, 5000, 5000, workspaceSupplied);
    }

    private static void assertDefinitivelyNotSent(DispatchReceipt receipt) {
        assertEquals(DispatchStatus.REJECTED, receipt.status());
        assertNotEquals(DispatchStatus.AMBIGUOUS, receipt.status());
    }

    private static DeadlineBoundSender deadlineBound(JavaMailSender sender, Runnable abort) {
        return new DeadlineBoundSender(sender, abort, (message, afterConnect) -> {
            afterConnect.run();
            sender.send(message);
        });
    }

    private static boolean runAcceptingRelay(ServerSocket server) throws IOException {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                Writer writer = new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.US_ASCII)) {
            writeLine(writer, "220 loopback ready");
            boolean readingData = false;
            boolean accepted = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (readingData) {
                    if (".".equals(line)) {
                        accepted = true;
                        readingData = false;
                        writeLine(writer, "250 accepted");
                    }
                } else if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                    writeLine(writer, "250 loopback");
                } else if (line.startsWith("MAIL FROM") || line.startsWith("RCPT TO")) {
                    writeLine(writer, "250 accepted");
                } else if ("DATA".equals(line)) {
                    readingData = true;
                    writeLine(writer, "354 end with dot");
                } else if ("QUIT".equals(line)) {
                    writeLine(writer, "221 closing");
                    return accepted;
                }
            }
            return accepted;
        }
    }

    private static void runSlowDripRelay(ServerSocket server, CountDownLatch dataAccepted) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                Writer writer = new OutputStreamWriter(
                        socket.getOutputStream(), java.nio.charset.StandardCharsets.US_ASCII)) {
            writeLine(writer, "220 loopback ready");
            boolean readingData = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (readingData) {
                    if (".".equals(line)) {
                        dataAccepted.countDown();
                        while (!socket.isClosed()) {
                            writer.write("2");
                            writer.flush();
                            Thread.sleep(50);
                        }
                    }
                    continue;
                }
                if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                    writeLine(writer, "250 loopback");
                } else if (line.startsWith("MAIL FROM") || line.startsWith("RCPT TO")) {
                    writeLine(writer, "250 accepted");
                } else if ("DATA".equals(line)) {
                    writeLine(writer, "354 end with dot");
                    readingData = true;
                } else if ("QUIT".equals(line)) {
                    writeLine(writer, "221 closing");
                    return;
                }
            }
        } catch (IOException exception) {
            return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeLine(Writer writer, String line) throws IOException {
        writer.write(line + "\r\n");
        writer.flush();
    }
}
