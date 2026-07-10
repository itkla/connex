package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

/**
 * Guardrails for the encryption guarantee matrix (#369, #375). App-level
 * encryption is allowed for never-searched credentials only; searchable CRM data
 * must rely on storage/database encryption, tenant isolation, RBAC, and audit.
 */
class EncryptionGuardrailArchTest {

    private static final Pattern CREATE_TABLE =
        Pattern.compile("^\\s*CREATE\\s+TABLE\\s+`?([a-z0-9_]+)`?\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_TABLE =
        Pattern.compile("^\\s*ALTER\\s+TABLE\\s+`?([a-z0-9_]+)`?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_DEFINITION =
        Pattern.compile("(?:^|,)\\s*(?:(?:ADD|MODIFY)\\s+)?(?:COLUMN\\s+)?`?([a-z0-9_]+)`?\\s+\\w+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANGE_COLUMN_DEFINITION =
        Pattern.compile("(?:^|,)\\s*CHANGE\\s+(?:COLUMN\\s+)?`?[a-z0-9_]+`?\\s+`?([a-z0-9_]+)`?\\s+\\w+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CRYPTO_COLUMN_NAME =
        Pattern.compile("(^encrypted_|_encrypted(?:_|$)|_enc$|ciphertext|data_key|_cipher(?:_|$)|"
            + "_secret_(?:id|ref|reference)$|_secret$|secret_(?:id|ref|reference)$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CRYPTO_COLUMN_DEFINITION =
        Pattern.compile("encrypted|ciphertext|secret:v1", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSUPPORTED_DOC_CLAIM =
        Pattern.compile("\\bE2EE\\b|zero[- ]knowledge|end-to-end encrypted|end-to-end encryption|"
            + "Connex cannot see|Connex cannot access|Connex cannot read|technically unable to decrypt|"
            + "customer[- ]only[- ]key|inaccessible to Connex|cannot access plaintext",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPORTED_DOC_POLICY_CONTEXT =
        Pattern.compile("do not say|must not(?: describe)?|does not make|do not make|"
            + "No for hosted|deny the claim|qualify it|used to deny",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPORTED_DOC_NEGATION_CONTEXT =
        Pattern.compile("\\b(?:is|are|does|do|must)\\s+not\\b.{0,120}"
            + "(?:E2EE|zero[- ]knowledge|end-to-end encrypted|end-to-end encryption|"
            + "customer[- ]only[- ]key|unable to decrypt|cannot access plaintext)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_CHANGE_FIELD =
        Pattern.compile("auditService\\.singleChange\\(\\s*\"([^\"]+)\"");
    private static final Pattern SECRET_AUDIT_FIELD =
        Pattern.compile("password$|token$|passwordEnc|clientSecret|privateKey|private_key|ciphertext|"
            + "encryptedDataKey|encrypted_data_key|encrypted$|_enc$|dataKey|data_key",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RISKY_SECRET_ACCESSOR =
        Pattern.compile("\\.get(?:PasswordEnc|OidcClientSecretEnc|SamlSpPrivateKeyEnc|Ciphertext|EncryptedDataKey)\\(");
    private static final Pattern LOG_AUDIT_EXCEPTION_STATEMENT =
        Pattern.compile("\\b(?:auditService\\.|log\\.|throw\\s+new)\\b");
    private static final Pattern RESPONSE_DTO_FIELD =
        Pattern.compile("\\bprivate\\s+\\w+(?:<[^>]+>)?\\s+([A-Za-z0-9_]+)\\b");
    private static final Pattern RESPONSE_DTO_SECRET_FIELD =
        Pattern.compile("password|clientSecret|privateKey|ciphertext|encryptedDataKey|encrypted_data_key|"
            + "secretReference|secretRef|secretValue", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_DTO_ALLOWED_SECRET_METADATA =
        Pattern.compile("hasPassword|hasClientSecret|secretId|secretCount|missingKeySecrets|disabledKeySecrets|"
            + "mismatchedSecrets|staleSecrets|totalSecrets|unsupportedAlgorithmSecrets",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CIPHER_API_REFERENCE =
        Pattern.compile("\\bjavax\\.crypto\\.Cipher\\b|\\bCipher\\s*\\.|"
            + "\\bCipher\\s+[A-Za-z_$][A-Za-z0-9_$]*\\b");
    private static final Pattern MYBATIS_TYPE_HANDLER_DECLARATION =
        Pattern.compile("\\bimplements\\b[^\\{;]*\\bTypeHandler\\b|"
            + "\\bextends\\b[^\\{;]*\\b(?:BaseTypeHandler|TypeReference)\\b");
    private static final Pattern MYBATIS_TYPE_HANDLER_ANNOTATION =
        Pattern.compile("@(?:[A-Za-z0-9_$.]+\\.)?(?:MappedTypes|MappedJdbcTypes)\\b");
    private static final Pattern JPA_CONVERTER_REFERENCE =
        Pattern.compile("\\bAttributeConverter\\b|"
            + "@(?:[A-Za-z0-9_$.]+\\.)?(?:Convert|ColumnTransformer)\\b");
    private static final Pattern TRANSPARENT_ENCRYPTION_REFERENCE =
        Pattern.compile("\\b(?:javax\\.crypto\\.)?Cipher\\b|\\.\\s*(?:encrypt|decrypt)\\s*\\(|"
            + "\\bSecretKeySpec\\b|secret:v1", Pattern.CASE_INSENSITIVE);
    private static final Pattern BINARY_COLUMN_DEFINITION =
        Pattern.compile("(?:^|,)\\s*(?:(?:ADD|MODIFY)\\s+)?(?:COLUMN\\s+)?`?([a-z0-9_]+)`?\\s+"
            + "(?:VARBINARY|BINARY|TINYBLOB|BLOB|MEDIUMBLOB|LONGBLOB)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANGE_BINARY_COLUMN_DEFINITION =
        Pattern.compile("(?:^|,)\\s*CHANGE\\s+(?:COLUMN\\s+)?`?[a-z0-9_]+`?\\s+`?([a-z0-9_]+)`?\\s+"
            + "(?:VARBINARY|BINARY|TINYBLOB|BLOB|MEDIUMBLOB|LONGBLOB)\\b", Pattern.CASE_INSENSITIVE);

    private static final Set<String> APPROVED_SECRET_COLUMNS = Set.of(
        "secret_value.encrypted_data_key",
        "secret_value.ciphertext",
        "workspace_mail_config.password_enc",
        "sso_connection.oidc_client_secret_enc",
        "sso_connection.saml_sp_private_key_enc");

    private static final Set<String> APPROVED_CIPHER_PACKAGES = Set.of(
        "ooo/klae/connex/backend/secrets/",
        "ooo/klae/connex/backend/sso/",
        "ooo/klae/connex/backend/mail/");

    private static final Set<String> EXPECTED_APPROVED_CIPHER_SITES = Set.of(
        "ooo/klae/connex/backend/secrets/SecretStoreCrypto.java",
        "ooo/klae/connex/backend/sso/AesGcm.java",
        "ooo/klae/connex/backend/mail/SecretCipher.java");

    private static final Set<String> CORE_CRM_TABLES = Set.of(
        "person",
        "person_share",
        "person_tag",
        "person_employment",
        "person_edge",
        "company",
        "company_share",
        "company_tag",
        "deal",
        "deal_person",
        "deal_collaborator",
        "deal_tag",
        "deal_stage_history",
        "note",
        "note_reference",
        "entity_reference",
        "activity",
        "task",
        "pipeline",
        "pipeline_share",
        "stage",
        "tag",
        "custom_field_definition",
        "custom_field_value",
        "introduction");

    private static final Set<String> APPROVED_CRM_BINARY_COLUMNS = Set.of();

    private static final Set<String> COLUMN_KEYWORDS = Set.of(
        "constraint", "primary", "unique", "key", "index", "fulltext", "spatial",
        "foreign", "check", "drop", "add");

    @Test
    void migrations_only_add_app_level_crypto_columns_for_approved_secrets() throws Exception {
        List<String> violations = new ArrayList<>();
        Set<String> seenApproved = new LinkedHashSet<>();
        Path migrations = repoRoot().resolve("backend/src/main/resources/db/migration");
        try (Stream<Path> files = Files.walk(migrations)) {
            List<Path> sqlFiles = files
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
            assertTrue(sqlFiles.size() >= 50,
                "Only found " + sqlFiles.size() + " migrations; the scan is likely misconfigured.");
            for (Path file : sqlFiles) {
                scanMigration(file, violations, seenApproved);
            }
        }

        List<String> stale = APPROVED_SECRET_COLUMNS.stream()
            .filter(column -> !seenApproved.contains(column))
            .sorted()
            .toList();
        assertTrue(stale.isEmpty(),
            "These approved encrypted/secret-reference columns were not found in migrations; remove stale "
                + "allowlist entries or update the table/column name: " + stale);
        assertTrue(violations.isEmpty(),
            "App-level encrypted/ciphertext/secret-reference columns are only approved for never-searched "
                + "secrets. Searchable CRM/business fields must use storage/database encryption instead. "
                + "Either remove the column or add an explicit approved-secret allowlist entry: " + violations);
    }

    @Test
    void customer_facing_text_does_not_make_unsupported_encryption_claims() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path path : customerFacingTextFiles()) {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String previous = i == 0 ? "" : lines.get(i - 1);
                String next = i + 1 >= lines.size() ? "" : lines.get(i + 1);
                String context = previous + " " + line + " " + next;
                if (UNSUPPORTED_DOC_CLAIM.matcher(line).find()
                    && !supportedDocClaimContext(context)) {
                    violations.add(relative(path) + ":" + (i + 1) + ": " + line.strip());
                }
            }
        }

        assertTrue(violations.isEmpty(),
            "Unsupported hosted-SaaS encryption claims must be denied or explicitly qualified to "
                + "customer-operated/on-prem deployments per ENCRYPTION_GUARANTEE_MATRIX.md: " + violations);
    }

    @Test
    void audit_change_fields_do_not_include_secret_material() throws Exception {
        List<String> violations = new ArrayList<>();
        violations.addAll(auditSetFieldViolations());
        violations.addAll(singleChangeFieldViolations());

        assertTrue(violations.isEmpty(),
            "Audit log changes must not include passwords, tokens, ciphertext, encrypted refs, or secret fields: "
                + violations);
    }

    @Test
    void secret_material_is_not_sent_to_logs_exceptions_or_response_dtos() throws Exception {
        List<String> violations = new ArrayList<>();
        violations.addAll(secretAccessorSinkViolations());
        violations.addAll(responseDtoSecretFieldViolations());

        assertTrue(violations.isEmpty(),
            "Secret plaintext, ciphertext, wrapped data keys, and encrypted references must not be logged, "
                + "placed in exception messages, or exposed from response DTOs: " + violations);
    }

    @Test
    void app_level_ciphers_are_confined_to_approved_encryption_packages() throws Exception {
        List<String> violations = new ArrayList<>();
        Set<String> seenApproved = new LinkedHashSet<>();
        Path main = repoRoot().resolve("backend/src/main/java");
        List<Path> javaFiles = javaSourceFiles(main);
        assertTrue(javaFiles.size() >= 100,
            "Only found " + javaFiles.size() + " backend Java files; the cipher scan is likely misconfigured.");

        for (Path file : javaFiles) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!CIPHER_API_REFERENCE.matcher(source).find()) {
                continue;
            }
            String sourcePath = main.relativize(file).toString().replace('\\', '/');
            if (APPROVED_CIPHER_PACKAGES.stream().anyMatch(sourcePath::startsWith)) {
                if (EXPECTED_APPROVED_CIPHER_SITES.contains(sourcePath)) {
                    seenApproved.add(sourcePath);
                }
                continue;
            }
            violations.add(relative(file));
        }

        List<String> missingApproved = EXPECTED_APPROVED_CIPHER_SITES.stream()
            .filter(site -> !seenApproved.contains(site))
            .sorted()
            .toList();
        assertTrue(missingApproved.isEmpty(),
            "Expected approved Cipher sites were not scanned as Cipher users: " + missingApproved);
        assertTrue(violations.isEmpty(),
            "App-level Cipher use is confined to approved encryption packages. Searchable CRM data must follow "
                + "ENCRYPTION_GUARANTEE_MATRIX.md and #375 instead of adding service, mapper, bean, or provider "
                + "encryption: " + violations);
    }

    @Test
    void mybatis_type_handlers_do_not_transparently_encrypt() throws Exception {
        List<String> violations = new ArrayList<>();
        Path main = repoRoot().resolve("backend/src/main/java");
        List<Path> javaFiles = javaSourceFiles(main);
        assertTrue(javaFiles.size() >= 100,
            "Only found " + javaFiles.size() + " backend Java files; the type-handler scan is likely misconfigured.");

        for (Path file : javaFiles) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            boolean typeHandler = file.getFileName().toString().endsWith("TypeHandler.java")
                && MYBATIS_TYPE_HANDLER_DECLARATION.matcher(source).find();
            typeHandler = typeHandler || MYBATIS_TYPE_HANDLER_ANNOTATION.matcher(source).find();
            boolean converter = JPA_CONVERTER_REFERENCE.matcher(source).find();
            if ((typeHandler || converter) && TRANSPARENT_ENCRYPTION_REFERENCE.matcher(source).find()) {
                violations.add(relative(file));
            }
        }

        assertTrue(violations.isEmpty(),
            "MyBatis type handlers and JPA-style converters must not transparently encrypt database columns. "
                + "Searchable CRM fields must use storage/database encryption per ENCRYPTION_GUARANTEE_MATRIX.md "
                + "and #375: " + violations);
    }

    @Test
    void core_crm_tables_have_no_unexplained_binary_columns() throws Exception {
        Set<String> foundBinaryColumns = new LinkedHashSet<>();
        Set<String> seenApproved = new LinkedHashSet<>();
        List<String> violationLocations = new ArrayList<>();
        Path migrations = repoRoot().resolve("backend/src/main/resources/db/migration");
        List<Path> sqlFiles;
        try (Stream<Path> files = Files.walk(migrations)) {
            sqlFiles = files
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
        assertTrue(sqlFiles.size() >= 50,
            "Only found " + sqlFiles.size() + " migrations; the binary-column scan is likely misconfigured.");

        for (Path file : sqlFiles) {
            scanCoreCrmBinaryColumns(file, foundBinaryColumns, seenApproved, violationLocations);
        }

        if (APPROVED_CRM_BINARY_COLUMNS.isEmpty()) {
            assertTrue(foundBinaryColumns.isEmpty(),
                "No core CRM binary columns are approved, but the migration scan found: " + foundBinaryColumns);
        }
        List<String> stale = APPROVED_CRM_BINARY_COLUMNS.stream()
            .filter(column -> !seenApproved.contains(column))
            .sorted()
            .toList();
        List<String> unexplained = foundBinaryColumns.stream()
            .filter(column -> !APPROVED_CRM_BINARY_COLUMNS.contains(column))
            .sorted()
            .toList();
        assertTrue(stale.isEmpty(),
            "These approved core CRM binary columns were not found; remove stale allowlist entries or update the "
                + "table/column name: " + stale);
        assertTrue(unexplained.isEmpty(),
            "Binary columns on searchable CRM tables require storage/database encryption and explicit review, "
                + "not an app-level blob: " + violationLocations);
    }

    private void scanMigration(Path file, List<String> violations, Set<String> seenApproved) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String table = null;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (trimmed.isBlank() || trimmed.startsWith("--")) {
                continue;
            }

            Matcher create = CREATE_TABLE.matcher(trimmed);
            if (create.find()) {
                table = create.group(1).toLowerCase(Locale.ROOT);
            }

            Matcher alter = ALTER_TABLE.matcher(trimmed);
            if (alter.find()) {
                table = alter.group(1).toLowerCase(Locale.ROOT);
            }

            if (table != null) {
                String columnScanLine = columnScanLine(trimmed, create, alter);
                for (String columnName : columnNames(columnScanLine)) {
                    String qualified = table + "." + columnName;
                    if (APPROVED_SECRET_COLUMNS.contains(qualified)) {
                        seenApproved.add(qualified);
                    }
                    if (!COLUMN_KEYWORDS.contains(columnName)
                        && (CRYPTO_COLUMN_NAME.matcher(columnName).find()
                            || CRYPTO_COLUMN_DEFINITION.matcher(trimmed).find())
                        && !APPROVED_SECRET_COLUMNS.contains(qualified)) {
                        violations.add(relative(file) + ":" + (i + 1) + ": " + qualified);
                    }
                }
            }

            if (trimmed.endsWith(";")) {
                table = null;
            }
        }
    }

    private void scanCoreCrmBinaryColumns(Path file, Set<String> foundBinaryColumns, Set<String> seenApproved,
            List<String> violationLocations) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String table = null;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (trimmed.isBlank() || trimmed.startsWith("--")) {
                continue;
            }

            Matcher create = CREATE_TABLE.matcher(trimmed);
            if (create.find()) {
                table = create.group(1).toLowerCase(Locale.ROOT);
            }

            Matcher alter = ALTER_TABLE.matcher(trimmed);
            if (alter.find()) {
                table = alter.group(1).toLowerCase(Locale.ROOT);
            }

            if (table != null && CORE_CRM_TABLES.contains(table)) {
                String columnScanLine = columnScanLine(trimmed, create, alter);
                for (String columnName : binaryColumnNames(columnScanLine)) {
                    String qualified = table + "." + columnName;
                    foundBinaryColumns.add(qualified);
                    if (APPROVED_CRM_BINARY_COLUMNS.contains(qualified)) {
                        seenApproved.add(qualified);
                    } else {
                        violationLocations.add(relative(file) + ":" + (i + 1) + ": " + qualified);
                    }
                }
            }

            if (trimmed.endsWith(";")) {
                table = null;
            }
        }
    }

    private List<String> columnNames(String line) {
        List<String> names = new ArrayList<>();
        Matcher changed = CHANGE_COLUMN_DEFINITION.matcher(line);
        while (changed.find()) {
            names.add(changed.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher column = COLUMN_DEFINITION.matcher(line);
        while (column.find()) {
            String name = column.group(1).toLowerCase(Locale.ROOT);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private List<String> binaryColumnNames(String line) {
        List<String> names = new ArrayList<>();
        Matcher changed = CHANGE_BINARY_COLUMN_DEFINITION.matcher(line);
        while (changed.find()) {
            names.add(changed.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher column = BINARY_COLUMN_DEFINITION.matcher(line);
        while (column.find()) {
            String name = column.group(1).toLowerCase(Locale.ROOT);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private String columnScanLine(String line, Matcher create, Matcher alter) {
        if (create.find(0)) {
            return line.substring(create.end()).strip();
        }
        if (alter.find(0)) {
            return line.substring(alter.end()).strip();
        }
        return line;
    }

    private boolean supportedDocClaimContext(String context) {
        return SUPPORTED_DOC_POLICY_CONTEXT.matcher(context).find()
            || SUPPORTED_DOC_NEGATION_CONTEXT.matcher(context).find();
    }

    private List<Path> customerFacingTextFiles() throws IOException {
        Set<Path> paths = new LinkedHashSet<>();
        Path root = repoRoot();
        try (Stream<Path> rootDocs = Files.list(root)) {
            rootDocs
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .forEach(paths::add);
        }

        for (String directory : List.of(
            ".github",
            "docs",
            "frontend/app",
            "frontend/components",
            "frontend/emails",
            "frontend/messages",
            "frontend/public")) {
            Path scanRoot = root.resolve(directory);
            if (Files.exists(scanRoot)) {
                try (Stream<Path> files = Files.walk(scanRoot)) {
                    files
                        .filter(Files::isRegularFile)
                        .filter(this::isCustomerFacingTextFile)
                        .forEach(paths::add);
                }
            }
        }
        return paths.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    private boolean isCustomerFacingTextFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".md") || name.endsWith(".mdx") || name.endsWith(".tsx") || name.endsWith(".json")
            || name.endsWith(".html");
    }

    private List<String> auditSetFieldViolations() {
        List<String> violations = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Service")));
        Set<BeanDefinition> services = scanner.findCandidateComponents("ooo.klae.connex.backend.services");
        assertTrue(services.size() >= 20,
            "Expected to scan backend services but found " + services.size()
                + "; the audit-field guard would pass vacuously.");

        for (BeanDefinition definition : services) {
            try {
                Class<?> service = Class.forName(definition.getBeanClassName());
                for (Field field : service.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || !field.getName().contains("AUDIT")) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof Set<?> fields) {
                        for (Object item : fields) {
                            if (item instanceof String fieldName && secretAuditField(fieldName)) {
                                violations.add(service.getSimpleName() + "." + field.getName() + ": " + fieldName);
                            }
                        }
                    }
                }
            } catch (ReflectiveOperationException e) {
                fail("Could not inspect service audit fields for " + definition.getBeanClassName() + ": "
                    + e.getMessage());
            }
        }
        return violations;
    }

    private List<String> singleChangeFieldViolations() throws IOException {
        List<String> violations = new ArrayList<>();
        Path services = repoRoot().resolve("backend/src/main/java/ooo/klae/connex/backend/services");
        try (Stream<Path> files = Files.walk(services)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher matcher = SINGLE_CHANGE_FIELD.matcher(lines.get(i));
                    if (matcher.find() && secretAuditField(matcher.group(1))) {
                        violations.add(relative(file) + ":" + (i + 1) + ": " + matcher.group(1));
                    }
                }
            }
        }
        return violations;
    }

    private List<String> secretAccessorSinkViolations() throws IOException {
        List<String> violations = new ArrayList<>();
        Path main = repoRoot().resolve("backend/src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String statement = statementStartingAt(lines, i);
                    if (LOG_AUDIT_EXCEPTION_STATEMENT.matcher(statement).find()
                        && RISKY_SECRET_ACCESSOR.matcher(statement).find()) {
                        violations.add(relative(file) + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            }
        }
        return violations;
    }

    private String statementStartingAt(List<String> lines, int start) {
        StringBuilder statement = new StringBuilder();
        for (int i = start; i < lines.size() && i < start + 12; i++) {
            String line = lines.get(i);
            statement.append(line).append('\n');
            if (line.contains(";")) {
                break;
            }
        }
        return statement.toString();
    }

    private List<String> responseDtoSecretFieldViolations() throws IOException {
        List<String> violations = new ArrayList<>();
        Path dto = repoRoot().resolve("backend/src/main/java/ooo/klae/connex/backend/dto");
        try (Stream<Path> files = Files.walk(dto)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith("Dto.java")).toList()) {
                if (isInboundDto(file)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher field = RESPONSE_DTO_FIELD.matcher(lines.get(i));
                    if (field.find()) {
                        String fieldName = field.group(1);
                        if (RESPONSE_DTO_SECRET_FIELD.matcher(fieldName).find()
                            && !RESPONSE_DTO_ALLOWED_SECRET_METADATA.matcher(fieldName).matches()) {
                            violations.add(relative(file) + ":" + (i + 1) + ": " + fieldName);
                        }
                    }
                }
            }
        }
        return violations;
    }

    private boolean isInboundDto(Path file) {
        String name = file.getFileName().toString();
        return name.contains("Request")
            || name.contains("Confirm")
            || name.equals("LoginDto.java")
            || name.equals("RegisterDto.java");
    }

    private boolean secretAuditField(String fieldName) {
        return SECRET_AUDIT_FIELD.matcher(fieldName).find();
    }

    private List<Path> javaSourceFiles(Path main) throws IOException {
        try (Stream<Path> files = Files.walk(main)) {
            return files
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static Path repoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.exists(cwd.resolve("backend"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        return parent == null ? cwd : parent;
    }

    private static String relative(Path path) {
        return repoRoot().relativize(path.toAbsolutePath()).toString();
    }
}
