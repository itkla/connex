package ooo.klae.connex.backend.connectedaccounts.capture;

import java.util.List;

/**
 * Effective admin/user/operator policy snapshot used by one sync page.
 */
public record CaptureExecutionPolicy(
    boolean enabled,
    boolean calendar,
    boolean mailInbox,
    boolean mailSent,
    int backfillDays,
    boolean includeBodies,
    String admissionMode,
    boolean excludePrivateEvents,
    boolean excludeInternalOnly,
    List<String> excludedDomains,
    List<String> excludedPeople,
    List<String> excludedConversations,
    long policyVersion
) {
    /** Defensively copies domain exclusions. */
    public CaptureExecutionPolicy {
        excludedDomains = List.copyOf(excludedDomains);
        excludedPeople = List.copyOf(excludedPeople);
        excludedConversations = List.copyOf(excludedConversations);
    }

    /** Whether this policy admits the named provider-neutral stream. */
    public boolean streamEnabled(String stream) {
        return switch (stream) {
            case "calendar" -> calendar;
            case "mail_inbox" -> mailInbox;
            case "mail_sent" -> mailSent;
            default -> false;
        };
    }
}
