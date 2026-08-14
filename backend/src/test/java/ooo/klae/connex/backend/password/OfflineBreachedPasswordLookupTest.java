package ooo.klae.connex.backend.password;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineBreachedPasswordLookupTest {
    private static final String FIRST = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8";
    private static final String SECOND = "C805A2FFAF2B30CC484C8D610DFCC5292C1794DE";

    @TempDir
    Path directory;

    @Test
    void validatesChecksumAndSearchesSortedFixedWidthCorpus() throws IOException {
        Path file = writeCorpus(FIRST + "\n" + SECOND + "\n");
        OfflineBreachedPasswordLookup lookup = lookup(file, sha256(file));

        lookup.validate();

        assertTrue(lookup.isBreached(FIRST));
        assertTrue(lookup.isBreached(SECOND));
        assertFalse(lookup.isBreached("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    void rejectsWrongChecksumAtStartup() throws IOException {
        Path file = writeCorpus(FIRST + "\n");
        OfflineBreachedPasswordLookup lookup = lookup(
                file, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertThrows(IllegalStateException.class, lookup::validate);
    }

    @Test
    void rejectsUnsortedCorpusAtStartup() throws IOException {
        Path file = writeCorpus(SECOND + "\n" + FIRST + "\n");
        OfflineBreachedPasswordLookup lookup = lookup(file, sha256(file));

        assertThrows(IllegalStateException.class, lookup::validate);
    }

    @Test
    void failsClosedIfVerifiedFileChangesAtRuntime() throws IOException {
        Path file = writeCorpus(FIRST + "\n");
        OfflineBreachedPasswordLookup lookup = lookup(file, sha256(file));
        lookup.validate();
        Files.writeString(file, SECOND + "\n" + FIRST + "\n", StandardCharsets.US_ASCII);

        assertThrows(BreachedPasswordSourceUnavailableException.class,
                () -> lookup.isBreached(FIRST));
    }

    @Test
    void rejectsSymbolicLinkAtStartup() throws IOException {
        Path target = writeCorpus(FIRST + "\n");
        Path link = directory.resolve("breached-passwords-link.txt");
        Files.createSymbolicLink(link, target);
        OfflineBreachedPasswordLookup lookup = lookup(link, sha256(target));

        assertThrows(IllegalStateException.class, lookup::validate);
    }

    @Test
    void failsClosedIfVerifiedFileIsReplacedWithSameSize() throws IOException {
        Path file = writeCorpus(FIRST + "\n");
        OfflineBreachedPasswordLookup lookup = lookup(file, sha256(file));
        lookup.validate();
        Path replacement = directory.resolve("replacement.txt");
        Files.writeString(replacement, SECOND + "\n", StandardCharsets.US_ASCII);
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);

        assertThrows(BreachedPasswordSourceUnavailableException.class,
                () -> lookup.isBreached(FIRST));
    }

    private Path writeCorpus(String content) throws IOException {
        Path file = directory.resolve("breached-passwords.txt");
        Files.writeString(file, content, StandardCharsets.US_ASCII);
        return file;
    }

    private static OfflineBreachedPasswordLookup lookup(Path file, String checksum) {
        BreachedPasswordProperties properties = new BreachedPasswordProperties();
        properties.setSource("OFFLINE");
        properties.setOfflineFile(file.toString());
        properties.setOfflineSha256(checksum);
        return new OfflineBreachedPasswordLookup(properties);
    }

    private static String sha256(Path file) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
