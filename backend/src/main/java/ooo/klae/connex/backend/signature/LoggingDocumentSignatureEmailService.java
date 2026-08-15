package ooo.klae.connex.backend.signature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Local fail-closed delivery seam that records no recipient identity or bearer token. */
@Service
@ConditionalOnProperty(
    prefix = "connex.signature",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true)
public class LoggingDocumentSignatureEmailService implements DocumentSignatureEmailService {
    private static final Logger log =
        LoggerFactory.getLogger(LoggingDocumentSignatureEmailService.class);

    @Override
    @Async
    public void send(
            int workspaceId,
            String recipientName,
            String recipientEmail,
            String documentTitle,
            String message,
            String acceptanceUrl,
            String locale) {
        log.warn("Document-signature email requested but signature delivery is disabled");
    }
}
