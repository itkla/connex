package ooo.klae.connex.backend.ai.assistant;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Computes immutable proposal deadlines from the original UTC database creation timestamp. */
public final class AiAssistantProposalExpiry {
    static final Duration PROPOSAL_REVIEW_WINDOW = Duration.ofHours(72);

    private AiAssistantProposalExpiry() {
    }

    /** Returns the original deadline, or null when the stored timestamp cannot arm a proposal. */
    public static Instant reviewExpiresAt(String storedCreatedAt) {
        if (storedCreatedAt == null || storedCreatedAt.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(storedCreatedAt.replace(' ', 'T'))
                    .toInstant(ZoneOffset.UTC).plus(PROPOSAL_REVIEW_WINDOW);
        } catch (DateTimeException exception) {
            return null;
        }
    }

    /** The exact deadline is expired; successful admission must occur strictly before it. */
    public static boolean isExpired(Instant reviewExpiresAt, Instant now) {
        return !now.isBefore(reviewExpiresAt);
    }

    /** The strict pending-list cutoff at the database's UTC microsecond precision. */
    public static LocalDateTime pendingCreatedAfter(Instant now) {
        return LocalDateTime.ofInstant(now.minus(PROPOSAL_REVIEW_WINDOW), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
    }
}
