package ooo.klae.connex.backend.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ooo.klae.connex.backend.config.DeploymentProperties;

class CapabilityProfileMatrixLoggerTest {

    @Test
    void logsManagedMailAsForbiddenUnderOnPrem() {
        assertEquals("Deployment capability matrix: profile=on-prem, forbidden=[MANAGED_MAIL], allowed=["
                + "SSO, SOCIAL_LOGIN_GOOGLE, SOCIAL_LOGIN_MICROSOFT, CONNECTED_ACCOUNTS_GOOGLE, "
                + "CONNECTED_ACCOUNTS_MICROSOFT, CONNECTED_CAPTURE_GOOGLE, CONNECTED_CAPTURE_MICROSOFT, "
                + "BUSINESS_CARD_SCANNING, BUSINESS_CARD_IMPORT, CAMPAIGN_DELIVERY, DOCUMENT_SIGNATURE]",
            capture(DeploymentProperties.PROFILE_ON_PREM));
    }

    @Test
    void logsEveryCapabilityAsAllowedUnderSaasAndSilo() {
        String allAllowed = "forbidden=[], allowed=["
            + "SSO, SOCIAL_LOGIN_GOOGLE, SOCIAL_LOGIN_MICROSOFT, CONNECTED_ACCOUNTS_GOOGLE, "
            + "CONNECTED_ACCOUNTS_MICROSOFT, CONNECTED_CAPTURE_GOOGLE, CONNECTED_CAPTURE_MICROSOFT, "
            + "MANAGED_MAIL, BUSINESS_CARD_SCANNING, BUSINESS_CARD_IMPORT, CAMPAIGN_DELIVERY, "
            + "DOCUMENT_SIGNATURE]";

        assertEquals("Deployment capability matrix: profile=saas, " + allAllowed,
            capture(DeploymentProperties.PROFILE_SAAS));
        assertEquals("Deployment capability matrix: profile=silo, " + allAllowed,
            capture(DeploymentProperties.PROFILE_SILO));
    }

    @Test
    void logsUnsetProfileSoExemptStartupsStillReportTheMatrix() {
        assertEquals("Deployment capability matrix: profile=unset, forbidden=[], allowed=["
                + "SSO, SOCIAL_LOGIN_GOOGLE, SOCIAL_LOGIN_MICROSOFT, CONNECTED_ACCOUNTS_GOOGLE, "
                + "CONNECTED_ACCOUNTS_MICROSOFT, CONNECTED_CAPTURE_GOOGLE, CONNECTED_CAPTURE_MICROSOFT, "
                + "MANAGED_MAIL, BUSINESS_CARD_SCANNING, BUSINESS_CARD_IMPORT, CAMPAIGN_DELIVERY, "
                + "DOCUMENT_SIGNATURE]",
            capture(""));
    }

    private static String capture(String profile) {
        DeploymentProperties properties = new DeploymentProperties();
        properties.setProfile(profile);
        Logger logger = (Logger) LoggerFactory.getLogger(CapabilityProfileMatrixLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new CapabilityProfileMatrixLogger(properties).run(null);
            assertEquals(1, appender.list.size(), "expected exactly one matrix line");
            return appender.list.getFirst().getFormattedMessage();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
