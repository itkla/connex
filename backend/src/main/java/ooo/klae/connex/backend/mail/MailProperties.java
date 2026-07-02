package ooo.klae.connex.backend.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Instance-wide SMTP transport configuration, the default sender used for
 * account-level mail (password reset, email change) and the fallback for any
 * workspace that has not configured its own SMTP. Bound from {@code connex.mail.*}
 * / {@code CONNEX_MAIL_*}. When {@link #isEnabled()} is false no transport is
 * built and delivery falls back to the logging seams.
 */
@Data
@Component
@ConfigurationProperties(prefix = "connex.mail")
public class MailProperties {

    /** Master switch: when false, no SMTP transport is built and real sending is disabled. */
    private boolean enabled = false;

    private String host;
    private int port = 587;
    private String username;
    private String password;

    /** Envelope/from address; falls back to {@link #username} when blank. */
    private String from;
    private String fromName = "Connex";

    private boolean starttls = true;
    private boolean ssl = false;
    private boolean auth = true;

    private int connectionTimeoutMs = 10_000;
    private int timeoutMs = 10_000;
    private int writeTimeoutMs = 10_000;

    /**
     * Base64-encoded AES key (128/192/256-bit) used to encrypt per-workspace SMTP
     * passwords at rest. Required only once a workspace stores its own SMTP password;
     * rotating it invalidates previously stored passwords.
     */
    private String secretKey;

    /** Frontend origin used to build workspace invite links (never derived from a request header). */
    private String appBaseUrl = "http://localhost:3000";
}
