package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import ooo.klae.connex.backend.beans.EmailChangeToken;
import ooo.klae.connex.backend.beans.PasswordResetToken;

/**
 * Executes V211 against connection-local legacy tables after password or MFA recovery has
 * advanced the account epoch. Temporary tables shadow the three real control tables only for
 * this connection; closing it discards the fixture without altering the lane's migrated schema.
 * HTTP rejection and post-upgrade issuance are exercised by the token lifecycle integration test.
 */
class CredentialTokenGenerationMigrationIntegrationTest {

    private static final String NAMESPACE = "credentialTokenGenerationFixture.";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyTokensRetainedAcrossRecoveryKeepUnknownGeneration(boolean passwordRecovery) throws Exception {
        String url = System.getenv("CONNEX_DB_URL");
        String username = System.getenv("CONNEX_DB_USERNAME");
        String password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(url != null && username != null && password != null,
                "CONNEX_DB_URL/CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD required for the migration test");
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "migration-fixtures/CredentialTokenGenerationMapper.xml";
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        try (Connection connection = DriverManager.getConnection(url, username, password);
                SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(connection)) {
            session.update(NAMESPACE + "createAccountTable");
            session.update(NAMESPACE + "createResetTable");
            session.update(NAMESPACE + "createEmailTable");
            session.insert(NAMESPACE + "issueLegacyAccount");
            session.insert(NAMESPACE + "issueLegacyResetTokens");
            session.insert(NAMESPACE + "issueLegacyEmailTokens");
            assertEquals(1, session.update(NAMESPACE + "recoverAccountBeforeUpgrade",
                    Map.of("passwordRecovery", passwordRecovery)));
            Integer recoveredEpoch = session.selectOne(NAMESPACE + "accountEpoch");
            assertEquals(4, recoveredEpoch);

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/control/V211__credential_token_generation.sql"));
            session.clearCache();

            for (String hash : new String[] {"a".repeat(64), "b".repeat(64)}) {
                PasswordResetToken token = session.selectOne(NAMESPACE + "resetToken", Map.of("tokenHash", hash));
                assertNotNull(token);
                assertNull(token.getCredentialGeneration(), "upgrade must not assign a recovered epoch to a legacy token");
                assertNull(token.getConsumedAt());
            }
            for (String hash : new String[] {"c".repeat(64), "d".repeat(64)}) {
                EmailChangeToken token = session.selectOne(NAMESPACE + "emailToken", Map.of("tokenHash", hash));
                assertNotNull(token);
                assertNull(token.getCredentialGeneration(), "upgrade must not assign a recovered epoch to a legacy token");
                assertNull(token.getConsumedAt());
            }
            Integer exchangedResets = session.selectOne(NAMESPACE + "exchangedResetCount");
            Integer exchangedEmails = session.selectOne(NAMESPACE + "exchangedEmailCount");
            assertEquals(1, exchangedResets);
            assertEquals(1, exchangedEmails);

            session.insert(NAMESPACE + "issueFreshResetToken");
            session.insert(NAMESPACE + "issueFreshEmailToken");
            PasswordResetToken reset = session.selectOne(NAMESPACE + "resetToken", Map.of("tokenHash", "e".repeat(64)));
            EmailChangeToken email = session.selectOne(NAMESPACE + "emailToken", Map.of("tokenHash", "f".repeat(64)));
            assertNotNull(reset);
            assertNotNull(email);
            assertEquals(recoveredEpoch, reset.getCredentialGeneration());
            assertEquals(recoveredEpoch, email.getCredentialGeneration());
        }
    }
}
