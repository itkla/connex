package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AiRunLeaseMapperContractArchTest {

    /**
     * Timestamp columns may only ever be assigned from the database clock, from another column of
     * the same row, or cleared. A bound parameter here would put a lease deadline on a JVM clock and
     * let instance skew move it. {@code GREATEST(CURRENT_TIMESTAMP(6), …)} is still the database's
     * own clock: it is how the release keeps the expiry CHECK satisfiable when that clock has
     * stepped backwards since the lease was acquired. {@code GREATEST(DATE_ADD(CURRENT_TIMESTAMP(6),
     * …), …)} is the same guard applied to a renewal's deadline.
     */
    private static final Pattern TIMESTAMP_ASSIGNMENT = Pattern.compile(
            "(acquired_at|heartbeat_at|expires_at|released_at)\\s*=\\s*(?!"
                    + "CURRENT_TIMESTAMP\\(6\\)|DATE_ADD\\(CURRENT_TIMESTAMP\\(6\\)"
                    + "|GREATEST\\(CURRENT_TIMESTAMP\\(6\\)"
                    + "|GREATEST\\(\\s*DATE_ADD\\(CURRENT_TIMESTAMP\\(6\\)|NULL)(\\S+)");

    @Test
    void noTimestampColumnIsAssignedFromABoundParameter() throws IOException {
        String xml = new String(
                new ClassPathResource("mappers/AiRunLeaseMapper.xml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        Matcher matcher = TIMESTAMP_ASSIGNMENT.matcher(xml);
        String offender = matcher.find() ? matcher.group() : "";

        assertTrue(
                offender.isEmpty(),
                "Lease timestamps must come from MySQL, found assignment: " + offender);
    }
}
