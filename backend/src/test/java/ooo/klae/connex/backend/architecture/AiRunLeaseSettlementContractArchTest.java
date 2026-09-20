package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Keeps the run lease's settlement contract stated where the next lease-subject author will read
 * it, and keeps the code that enforces it from drifting away from that statement.
 *
 * <p>The contract is that a settlement takeover and the subject's terminal write commit together,
 * in one transaction, with the lease taken last. It is a concurrency rule rather than an
 * implementation detail: splitting the two commits opens a window in which the epoch has already
 * moved while the subject still reads as running, so the owner the settler just fenced out can
 * settle the subject itself and retire the settler's lease. Leaving that rule only in the
 * implementation and its SPI Javadoc would make {@code docs/backend/LOCKING.md} — the authoritative
 * lock-order guide {@code backend/AGENTS.md} points every backend author at — incomplete for the
 * next {@code AiRunLeaseSubjectHandler}.
 *
 * <p>The propagation assertion is the other half. {@code MANDATORY} is what makes the rule fail
 * closed for a handler that forgets to open its terminal transaction; relaxing it to
 * {@code REQUIRED} would silently restore the standalone takeover the guide says is a defect, so
 * the guide and the annotation are asserted together rather than separately.
 */
class AiRunLeaseSettlementContractArchTest {

    private static final Path LEASE_SERVICE = Path.of(
            "src/main/java/ooo/klae/connex/backend/ai/lease/AiRunLeaseService.java");
    private static final String SETTLEMENT_SECTION = "#### Settlement is one transaction";
    private static final List<String> REQUIRED_GUIDE_PHRASES = List.of(
            "takeOverForSettlement",
            "MANDATORY",
            "are **one transaction**",
            "`ai_chat_session` → `ai_chat_turn` → `ai_run_lease`",
            "revived-owner");

    @Test
    void theSettlementTakeoverDeclaresMandatoryPropagation() throws IOException {
        String source = Files.readString(repoRoot().resolve("backend").resolve(LEASE_SERVICE),
                StandardCharsets.UTF_8);
        int declaration = source.indexOf("public Optional<AiRunLease> takeOverForSettlement(");
        assertTrue(declaration > 0,
            "AiRunLeaseService.takeOverForSettlement is gone — this guard needs re-aiming.");
        String annotation = source.substring(
            source.lastIndexOf("@Transactional", declaration), declaration);
        assertTrue(annotation.contains("Propagation.MANDATORY"),
            "takeOverForSettlement must declare MANDATORY propagation so a subject handler that "
                + "never opened its terminal transaction is refused instead of committing a "
                + "standalone takeover: " + annotation.strip());
    }

    @Test
    void theLockingGuideRecordsTheAtomicSettlementContract() throws IOException {
        String guide = Files.readString(
            repoRoot().resolve("docs/backend/LOCKING.md"), StandardCharsets.UTF_8);
        int section = guide.indexOf(SETTLEMENT_SECTION);
        assertTrue(section > 0,
            "docs/backend/LOCKING.md must carry an '" + SETTLEMENT_SECTION + "' rule under its AI "
                + "run lease section — the atomic settlement contract is a lock-order rule every "
                + "future lease subject owes, not an AiChatTurn implementation detail.");
        String body = guide.substring(section, nextSectionAfter(guide, section))
                .replaceAll("\\s+", " ");
        List<String> missing = new ArrayList<>();
        for (String phrase : REQUIRED_GUIDE_PHRASES) {
            if (!body.contains(phrase)) {
                missing.add(phrase);
            }
        }
        assertTrue(missing.isEmpty(),
            "The settlement contract in docs/backend/LOCKING.md must still state the rule it was "
                + "written for; these are missing: " + missing);
    }

    private static int nextSectionAfter(String guide, int section) {
        int next = guide.indexOf("\n## ", section);
        return next < 0 ? guide.length() : next;
    }

    private Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null
                && !Files.exists(current.resolve("backend/src/main/resources/db/migration"))) {
            current = current.getParent();
        }
        assertTrue(current != null,
            "Could not locate the repository root from " + Path.of("").toAbsolutePath());
        return current;
    }
}
