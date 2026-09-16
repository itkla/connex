package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Keeps account recovery mutually evicting across the emailed credential families.
 *
 * <p>A password reset exists to evict whoever held the old password, and a verified email change
 * exists to move the recovery mailbox. Either one leaves the other family's outstanding tokens as
 * a pre-positioned bypass, so both writers must invalidate both families while holding the account
 * lock. Nothing in the type system provides that; these guards make a new credential writer that
 * forgets one half fail at build time rather than silently on a live account.
 *
 * <p>The eviction assertions are anchored inside the method that performs the credential write, not
 * merely somewhere in the file: both services legitimately call {@code lockById} and
 * {@code invalidateForUser} from unrelated methods, so a file-wide containment check would still
 * pass after the eviction was dropped from the write itself.
 */
class AccountRecoveryTokenInvalidationArchTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path PASSWORD_RESET_SERVICE =
            Path.of("ooo/klae/connex/backend/services/PasswordResetService.java");
    private static final Path EMAIL_CHANGE_SERVICE =
            Path.of("ooo/klae/connex/backend/services/EmailChangeService.java");
    private static final Path USER_SERVICE =
            Path.of("ooo/klae/connex/backend/services/UserService.java");
    private static final Path USER_CONTROLLER =
            Path.of("ooo/klae/connex/backend/controllers/UserController.java");
    private static final Path USER_DTO = Path.of("ooo/klae/connex/backend/dto/UserDto.java");
    private static final String PASSWORD_WRITE = "userMapper.updatePasswordHash(";
    private static final String EMAIL_WRITE = "userMapper.updateEmail(";
    private static final String GENERAL_ACCOUNT_WRITE = "userMapper.update(";
    private static final String PROFILE_UPDATE = "userService.update(";
    private static final String PASSWORD_HASH_ASSIGNMENT = "setPasswordHash(";
    private static final String RESET_INVALIDATION = "passwordResetTokenMapper.invalidateForUser(";
    private static final String EMAIL_CHANGE_INVALIDATION = "emailChangeTokenMapper.invalidateForUser(";
    private static final String ACCOUNT_LOCK = "userMapper.lockById(";
    private static final String LOCKED_ACCOUNT_READ = "userMapper.getUserByIdForShare(token.getUserId())";
    private static final String GENERATION_COMPARISON =
            "!token.getCredentialGeneration().equals(user.getSessionEpoch())";
    private static final Pattern METHOD_SIGNATURE =
            Pattern.compile("\\n {4}(?:public|protected|private) ");

    @Test
    void thePasswordHashIsWrittenOnlyByTheResetChokePoint() throws IOException {
        assertEquals(List.of(PASSWORD_RESET_SERVICE), sourcesContaining(PASSWORD_WRITE));
    }

    @Test
    void theAccountEmailIsWrittenOnlyByTheVerifiedChangeChokePoint() throws IOException {
        assertEquals(List.of(EMAIL_CHANGE_SERVICE), sourcesContaining(EMAIL_WRITE));
    }

    @Test
    void everyCredentialWriteEvictsBothEmailedTokenFamiliesUnderTheAccountLock() throws IOException {
        List<Path> writers = Stream.concat(
                sourcesContaining(PASSWORD_WRITE).stream(),
                sourcesContaining(EMAIL_WRITE).stream())
            .distinct()
            .sorted()
            .toList();

        assertTrue(writers.size() >= 2,
                "credential writers were renamed away from these guards; re-anchor them");
        for (Path writer : writers) {
            String source = Files.readString(SOURCE_ROOT.resolve(writer));
            assertCredentialWrites(writer, source);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "void resetPasswordByHash(",
        "public void\n        resetPasswordByHash(",
        "private String field;\n\n    void resetPasswordByHash("
    })
    void credentialGuardRejectsUnrecognizedWriterSignatures(String signature) throws IOException {
        String source = Files.readString(SOURCE_ROOT.resolve(PASSWORD_RESET_SERVICE))
            .replace("public void resetPasswordByHash(", signature);

        assertThrows(AssertionError.class, () -> assertCredentialWrites(PASSWORD_RESET_SERVICE, source));
    }

    @ParameterizedTest
    @ValueSource(strings = {PASSWORD_WRITE, EMAIL_WRITE})
    void credentialGuardRejectsAdditionalWrites(String write) throws IOException {
        String source = Files.readString(SOURCE_ROOT.resolve(PASSWORD_RESET_SERVICE));
        String additionalWriter = "\n    public void additionalWriter() {\n        " + write + "1, value);\n    }\n";
        String changed = source.replace("\n}", additionalWriter + "\n}");

        assertThrows(AssertionError.class, () -> assertCredentialWrites(PASSWORD_RESET_SERVICE, changed));
    }

    @Test
    void credentialGuardAcceptsInvalidationBeforeTheWrite() {
        String source = """
            class Writer {
                public void resetPasswordByHash() {
                    userMapper.lockById(1);
                    passwordResetTokenMapper.invalidateForUser(1);
                    emailChangeTokenMapper.invalidateForUser(1);
                    userMapper.updatePasswordHash(1, value);
                }
            }
            """;

        assertCredentialWrites(PASSWORD_RESET_SERVICE, source);
    }

    @Test
    void theGeneralAccountUpdateCannotCarryCredentialColumns() throws IOException {
        assertEquals(List.of(USER_SERVICE), sourcesContaining(GENERAL_ACCOUNT_WRITE),
                GENERAL_ACCOUNT_WRITE + " has one caller by design");
        assertEquals(List.of(USER_CONTROLLER), sourcesContaining(PROFILE_UPDATE),
                "the profile update must stay reachable only from the self-service controller");

        String userService = Files.readString(SOURCE_ROOT.resolve(USER_SERVICE));
        String profileUpdate = methodBodyContaining(
                USER_SERVICE, userService, userService.indexOf(GENERAL_ACCOUNT_WRITE), "update");
        assertTrue(profileUpdate.contains("User profile = new User();"));
        assertTrue(profileUpdate.contains("userMapper.update(profile);"));
        assertFalse(profileUpdate.contains("setEmail("));
        assertFalse(profileUpdate.contains(PASSWORD_HASH_ASSIGNMENT));
        String mapper = Files.readString(Path.of("src/main/resources/mappers/UserMapper.xml"));
        int updateAt = mapper.indexOf("<update id=\"update\">");
        assertTrue(updateAt >= 0);
        String update = mapper.substring(updateAt, mapper.indexOf("</update>", updateAt));
        assertFalse(update.contains("email"), "profile SQL must never write the recovery mailbox");
        assertFalse(update.contains("password_hash"), "profile SQL must never write a credential");
        assertFalse(update.contains("passwordHash"), "profile SQL must never bind a credential");
        for (Path source : List.of(USER_SERVICE, USER_CONTROLLER, USER_DTO)) {
            assertFalse(Files.readString(SOURCE_ROOT.resolve(source)).contains(PASSWORD_HASH_ASSIGNMENT),
                    source + " must never populate a password hash on the bean handed to "
                        + GENERAL_ACCOUNT_WRITE + ", which would bypass the reset choke point");
        }
    }

    @Test
    void emailedTokenIssuancePersistsTheLockedAccountGeneration() throws IOException {
        for (String family : List.of("PasswordReset", "EmailChange")) {
            Path service = family.equals("PasswordReset") ? PASSWORD_RESET_SERVICE : EMAIL_CHANGE_SERVICE;
            String mapperName = family.equals("PasswordReset") ? "passwordResetTokenMapper" : "emailChangeTokenMapper";
            String source = Files.readString(SOURCE_ROOT.resolve(service));
            String body = methodBodyContaining(service, source, source.indexOf(mapperName + ".insert("),
                    family.equals("PasswordReset") ? "requestReset" : "requestChange");
            assertOrdered(service, body, "userMapper.getUserByIdForShare(user.getId())",
                    body.indexOf(mapperName + ".insert("), " must issue from the locked account generation");
            assertTrue(body.contains("lockedUser.getSessionEpoch() == null"));
            assertTrue(body.contains("user = lockedUser;"));
            assertTrue(body.contains("tokenExpiryMinutes, user.getSessionEpoch())"));
            String mapper = Files.readString(Path.of("src/main/resources/mappers/" + family + "TokenMapper.xml"));
            int insertAt = mapper.indexOf("<insert id=\"insert\">");
            String insert = mapper.substring(insertAt, mapper.indexOf("</insert>", insertAt));
            assertTrue(insert.contains("credential_generation"));
            assertTrue(insert.contains("#{credentialGeneration}"));
        }
    }

    @Test
    void everyEmailedTokenExchangeAndConfirmationChecksTheLockedAccountGeneration() throws IOException {
        assertGenerationChecks(PASSWORD_RESET_SERVICE, Files.readString(SOURCE_ROOT.resolve(PASSWORD_RESET_SERVICE)));
        assertGenerationChecks(EMAIL_CHANGE_SERVICE, Files.readString(SOURCE_ROOT.resolve(EMAIL_CHANGE_SERVICE)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"userMapper.getUserById(token.getUserId())", "userMapper.currentSessionEpoch(token.getUserId())"})
    void generationGuardRejectsUnlockedAccountReads(String replacement) throws IOException {
        String source = Files.readString(SOURCE_ROOT.resolve(EMAIL_CHANGE_SERVICE))
            .replace(LOCKED_ACCOUNT_READ, replacement);
        assertThrows(AssertionError.class, () -> assertGenerationChecks(EMAIL_CHANGE_SERVICE, source));
    }

    private static void assertGenerationChecks(Path service, String source) {
        boolean reset = service.equals(PASSWORD_RESET_SERVICE);
        String mapper = reset ? "passwordResetTokenMapper" : "emailChangeTokenMapper";
        for (String action : List.of("claimExchange", "markConsumed")) {
            int actionAt = source.indexOf(mapper + "." + action + "(");
            String method = action.equals("claimExchange") ? "exchangeToken"
                    : reset ? "resetPasswordByHash" : "confirmChangeByHash";
            String body = methodBodyContaining(service, source, actionAt, method);
            int readAt = body.indexOf(LOCKED_ACCOUNT_READ);
            int comparisonAt = body.indexOf(GENERATION_COMPARISON);
            assertOrdered(service, body, ACCOUNT_LOCK, readAt, " must lock before reading the generation");
            assertOrdered(service, body, LOCKED_ACCOUNT_READ, comparisonAt, " must compare the locked generation");
            assertOrdered(service, body, "token.getCredentialGeneration() == null", comparisonAt,
                    " must refuse missing generations");
            assertOrdered(service, body, GENERATION_COMPARISON, body.indexOf(mapper + "." + action + "("),
                    " must refuse stale generations before exchanging or consuming");
            assertTrue(body.substring(comparisonAt).contains("throw invalidLink();"));
        }
    }

    private static void assertOrdered(Path source, String body, String earlier, int laterAt, String requirement) {
        int earlierAt = body.indexOf(earlier);
        assertTrue(earlierAt >= 0 && earlierAt < laterAt, source + requirement);
    }

    private static void assertCredentialWrites(Path writer, String source) {
        for (String write : List.of(PASSWORD_WRITE, EMAIL_WRITE)) {
            String expectedMethod = write.equals(PASSWORD_WRITE) ? "resetPasswordByHash" : "confirmChangeByHash";
            for (int callAt = source.indexOf(write); callAt >= 0; callAt = source.indexOf(write, callAt + write.length())) {
                String body = methodBodyContaining(writer, source, callAt, expectedMethod);
                assertOrdered(writer, body, ACCOUNT_LOCK, body.indexOf(write),
                        " must take the account lock before writing the credential");
                assertTrue(body.contains(RESET_INVALIDATION),
                        writer + " must invalidate outstanding password-reset tokens in the locked method");
                assertTrue(body.contains(EMAIL_CHANGE_INVALIDATION),
                        writer + " must invalidate outstanding email-change tokens in the locked method");
            }
        }
    }

    /**
     * Returns the body of the class-level method that performs the given call, so an ordering
     * assertion cannot be satisfied by an unrelated method elsewhere in the same file.
     */
    private static String methodBodyContaining(Path source, String content, int callAt, String expectedMethod) {
        assertTrue(callAt >= 0, source + " no longer contains the expected call in " + expectedMethod);
        Matcher signatures = METHOD_SIGNATURE.matcher(content);
        int start = -1;
        while (signatures.find() && signatures.start() < callAt) {
            start = signatures.start();
        }
        assertTrue(start >= 0, source + " has no enclosing method for " + expectedMethod);
        int signatureEnd = content.indexOf('\n', start + 1);
        assertTrue(signatureEnd > start, source + " has no complete signature for " + expectedMethod);
        String signature = content.substring(start, signatureEnd);
        Matcher methodName = Pattern.compile("([\\w$]+)\\s*\\(").matcher(signature);
        assertTrue(methodName.find(), source + " must declare " + expectedMethod + " on the modifier line");
        assertEquals(expectedMethod, methodName.group(1), source + " has an unexpected credential-write method");
        int end = content.indexOf("\n    }", signatureEnd);
        assertTrue(end > callAt, source + " call must be inside " + expectedMethod);
        return content.substring(start, end);
    }

    private static List<Path> sourcesContaining(String needle) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, needle))
                    .map(SOURCE_ROOT::relativize)
                    .sorted()
                    .toList();
        }
    }

    private static boolean contains(Path path, String needle) {
        try {
            return Files.readString(path).contains(needle);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect the account recovery token boundary");
        }
    }
}
