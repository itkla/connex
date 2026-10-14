package ooo.klae.connex.backend.delivery.provider.smtp;

import java.io.OutputStream;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;

import org.eclipse.angus.mail.smtp.SMTPTransport;

/**
 * Keeps SMTP commands and responses observable while suppressing all MIME DATA tracing.
 * Suppression begins when Angus returns the DATA stream after its successful 354 response, without
 * copying or pre-serializing the message. BDAT streams receive the same protection. Dot stuffing
 * distinguishes the standalone terminator from every body line, so logging resumes before the
 * final server response without inspecting MIME encodings.
 */
public final class BodySafeSmtpTransport extends SMTPTransport {

    /** Uses the SMTP session's native connection, TLS, socket, and cancellation settings. */
    public BodySafeSmtpTransport(Session session, URLName url) {
        super(session, url);
        SmtpDiagnosticLogFilter.install();
    }

    @Override
    protected OutputStream data() throws MessagingException {
        OutputStream stream = super.data();
        SmtpDiagnosticLogFilter.beginDataBody();
        return stream;
    }

    @Override
    protected OutputStream bdat() throws MessagingException {
        OutputStream stream = super.bdat();
        SmtpDiagnosticLogFilter.beginDataBody();
        return stream;
    }

    @Override
    public synchronized void sendMessage(Message message, Address[] addresses) throws MessagingException {
        try {
            super.sendMessage(message, addresses);
        } finally {
            SmtpDiagnosticLogFilter.endDataBody();
        }
    }

    @Override
    public synchronized void close() throws MessagingException {
        try {
            super.close();
        } finally {
            SmtpDiagnosticLogFilter.endDataBody();
        }
    }
}
