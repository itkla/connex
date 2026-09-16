package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards, through method-body ordering assertions, the coupling that makes
 * {@code AccountDeletionReservationRead} safe. The owner-recovery guards decide whether a workspace
 * still has an owner able to restore authority while holding that workspace's root exclusively, and
 * then read reservation flags in a separate {@code READ_COMMITTED} transaction that takes no user
 * lock. That read only observes a committed lease, so a writer that installs or extends a lease
 * without holding the owned workspace roots could commit its lease after a recovery check has
 * already counted the owner as available.
 *
 * <p>Every writer of {@code account_deletion_reservation_until} therefore takes the reserving
 * account's owned workspace roots before updating the lease and holds them through commit. This
 * source check is a rot alarm on that coupling, not the proof: the proof is the exclusive workspace
 * root that both sides contend on. Release and expiry are exempt because they restore availability.
 *
 * @see ooo.klae.connex.backend.services.AccountDeletionReservationRead
 */
class AccountDeletionReservationFenceArchTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path TRANSACTION_SOURCE = SOURCE_ROOT.resolve(
        "ooo/klae/connex/backend/services/UserDeletionTransaction.java");

    private static final List<String> RESERVATION_WRITE_CALLS = List.of(
        "userMapper.reserveAccountDeletion(",
        "userMapper.renewAccountDeletionReservation(");

    private static final String OWNED_WORKSPACE_ROOT_CALL =
        "workspaceService.lockAccountWorkspaceRoots(";

    @Test
    void everyReservationWriterTakesTheOwnedWorkspaceRoots() throws IOException {
        List<String> writers = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String body = Files.readString(source, StandardCharsets.UTF_8);
                if (RESERVATION_WRITE_CALLS.stream().noneMatch(body::contains)) {
                    continue;
                }
                writers.add(SOURCE_ROOT.relativize(source).toString());
                for (String call : RESERVATION_WRITE_CALLS) {
                    assertEquals(1, body.split(Pattern.quote(call), -1).length - 1,
                        "Each reservation write must have exactly one method-body guard: "
                            + source + " / " + call);
                }
            }
        }
        assertEquals(List.of(SOURCE_ROOT.relativize(TRANSACTION_SOURCE).toString()), writers,
            "Reservation writers must remain covered by the explicit method-body guards");

        String source = Files.readString(TRANSACTION_SOURCE, StandardCharsets.UTF_8);
        String reserve = methodBody(source, "public void reserve(int id, String owner)");
        assertBefore(reserve, "guard(id, false);", "userMapper.reserveAccountDeletion(");
        String guard = methodBody(source,
            "private AccountDeletionGuard guard(int id, boolean includeCredentialReferences)");
        assertBefore(guard, OWNED_WORKSPACE_ROOT_CALL, "return new AccountDeletionGuard(");
        String renew = methodBody(source, "public void renew(int id, String owner)");
        assertBefore(renew, OWNED_WORKSPACE_ROOT_CALL,
            "userMapper.renewAccountDeletionReservation(");
    }

    private static void assertBefore(String body, String requiredCall, String laterCall) {
        int requiredPosition = body.indexOf(requiredCall);
        int laterPosition = body.indexOf(laterCall);
        assertTrue(requiredPosition >= 0 && laterPosition > requiredPosition,
            requiredCall + " must precede " + laterCall + " inside its method body");
    }

    private static String methodBody(String source, String signature) {
        int signatureStart = source.indexOf("    " + signature + " {");
        assertTrue(signatureStart >= 0, "Missing reservation-fence method: " + signature);
        int openingBrace = source.indexOf('{', signatureStart);
        int depth = 1;
        for (int index = openingBrace + 1; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(openingBrace + 1, index);
            }
        }
        throw new AssertionError("Unclosed reservation-fence method: " + signature);
    }
}
