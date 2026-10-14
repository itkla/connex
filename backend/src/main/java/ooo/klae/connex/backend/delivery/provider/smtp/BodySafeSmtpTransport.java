package ooo.klae.connex.backend.delivery.provider.smtp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

/**
 * Keeps SMTP commands and responses observable while suppressing all MIME DATA tracing.
 * Suppression begins when the provider serializes MIME, after its DATA response. Dot stuffing
 * distinguishes the standalone terminator from every body line, so logging resumes before the
 * final server response without inspecting MIME encodings.
 */
public final class BodySafeSmtpTransport extends Transport {
    private final Transport delegate;

    /** Wraps the configured provider transport without changing connection or TLS selection. */
    public BodySafeSmtpTransport(Session session, Transport delegate) {
        super(session, null);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        SmtpDiagnosticLogFilter.install();
    }

    @Override
    protected boolean protocolConnect(String host, int port, String user, String password)
            throws MessagingException {
        delegate.connect(host, port, user, password);
        return true;
    }

    @Override
    public synchronized boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void sendMessage(Message message, Address[] addresses) throws MessagingException {
        try {
            delegate.sendMessage(message instanceof MimeMessage mime ? new BodySafeMimeMessage(mime) : message,
                    addresses);
        } finally {
            SmtpDiagnosticLogFilter.endDataBody();
        }
    }

    @Override
    public synchronized void close() throws MessagingException {
        try {
            delegate.close();
        } finally {
            SmtpDiagnosticLogFilter.endDataBody();
            super.close();
        }
    }

    private static final class BodySafeMimeMessage extends MimeMessage {
        private BodySafeMimeMessage(MimeMessage source) throws MessagingException {
            super(source);
        }

        @Override
        public void writeTo(OutputStream stream, String[] ignoredHeaders) throws IOException, MessagingException {
            SmtpDiagnosticLogFilter.beginDataBody();
            super.writeTo(stream, ignoredHeaders);
        }
    }
}
