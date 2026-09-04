package ooo.klae.connex.backend.delivery.provider.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mail.SmtpDestinationGuard;

class SmtpDeliveryProviderTest {

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
        when(senderFactory.deadlineBoundForConfig(eq(config), eq(address), anyLong()))
                .thenReturn(new DeadlineBoundSender(sender, () -> { }));
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
    void instanceDefaultRetainsHostnameRoutingAndHardDeadlineCancellation() throws Exception {
        JavaMailSenderFactory senderFactory = mock(JavaMailSenderFactory.class);
        SmtpDestinationGuard destinationGuard = mock(SmtpDestinationGuard.class);
        JavaMailSender sender = mock(JavaMailSender.class);
        ResolvedMailConfig config = config(false);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        CountDownLatch abortCalled = new CountDownLatch(1);
        when(senderFactory.deadlineBoundForConfig(eq(config), eq(null), anyLong()))
                .thenReturn(new DeadlineBoundSender(sender, abortCalled::countDown));
        when(sender.createMimeMessage()).thenReturn(mime);
        doAnswer(invocation -> {
            abortCalled.await(5, TimeUnit.SECONDS);
            return null;
        }).when(sender).send(mime);
        SmtpDeliveryProvider provider =
                new SmtpDeliveryProvider(senderFactory, destinationGuard);
        try {
            DispatchReceipt receipt = provider.dispatch(target(config), request(
                    System.nanoTime() + Duration.ofMillis(500).toNanos()));

            assertEquals(0, abortCalled.getCount());
            assertEquals(DispatchStatus.AMBIGUOUS, receipt.status());
            verify(destinationGuard, never()).resolveForSend(config);
            verify(senderFactory).deadlineBoundForConfig(eq(config), eq(null), anyLong());
        } finally {
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
