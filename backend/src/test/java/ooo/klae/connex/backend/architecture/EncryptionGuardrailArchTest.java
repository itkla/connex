package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.web.util.HtmlUtils;

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
        Pattern.compile("(?:\\bnot\\b(?!\\s+only\\b).{0,120}"
            + "(?:E2EE|zero[- ]knowledge|end-to-end encrypted|end-to-end encryption|"
            + "customer[- ]only[- ]key|unable to decrypt|cannot access plaintext)|"
            + "(?:E2EE|zero[- ]knowledge|end-to-end encrypted|end-to-end encryption|"
            + "customer[- ]only[- ]key|unable to decrypt|cannot access plaintext).{0,80}"
            + "\\b(?:not|never)\\b(?!\\s+only\\b))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CUSTOMER_OPERATED_QUALIFIER = Pattern.compile(
        "customer-operated/on-prem only", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOSTED_DEPLOYMENT_CONTEXT = Pattern.compile(
        "\\b(?:hosted|SaaS|Connex-operated)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSTFIX_CUSTOMER_OPERATED_QUALIFIER = Pattern.compile(
        "^(?:.{0,80}\\b(?:is|are|remains?|applies?|available|limited|restricted|only|for)\\b.{0,60}|"
            + "\\s*\\([^)]{0,80})customer-operated/on-prem only",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DOCUMENT_CLAUSE_BOUNDARY = Pattern.compile(
        "\\s*(?:;|\\u2014|\\u2013|\\bbut\\b|\\bhowever\\b|\\bwhile\\b|\\bwhereas\\b|\\byet\\b)\\s*",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_BREAKING_PREDICATE = Pattern.compile(
        "\\b(?:is|are|was|were|does|do|did|has|have|can|could|will|would|provides|offers|claims)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_CHANGE_FIELD =
        Pattern.compile("\\bsingleChange\\(\\s*\"([^\"]+)\"");
    private static final Pattern SECRET_AUDIT_FIELD =
        Pattern.compile("password$|token$|passwordEnc|clientSecret|privateKey|private_key|ciphertext|"
            + "encryptedDataKey|encrypted_data_key|encrypted$|_enc$|dataKey|data_key",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RISKY_SECRET_ACCESSOR = Pattern.compile(
        "\\.(?:get)?[A-Za-z0-9_$]*(?:Password|Passphrase|Token|ClientSecret|PrivateKey|SecretAccessKey|"
            + "ApiKey|HmacSecret|ServiceAccountJson|Plaintext|SecretValue|Ciphertext|EncryptedDataKey)"
            + "(?:Enc|Hash|Pem|Base64|Value)?\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_VALUE_ACCESSOR = Pattern.compile(
        "\\.(?:(?:get|is)[A-Za-z0-9_$]*(?:Password|Passphrase|Token|ClientSecret|PrivateKey|"
            + "SecretAccessKey|ApiKey|HmacSecret|ServiceAccountJson|Plaintext|SecretValue|Ciphertext|"
            + "EncryptedDataKey)|(?:RawToken|Token|CurrentPassword|NewPassword|Password|Passphrase|ClientSecret|"
            + "PrivateKey|SecretAccessKey|ApiKey|HmacSecret|ServiceAccountJson|Plaintext|SecretValue|Ciphertext|"
            + "EncryptedDataKey))(?:Enc|Hash|Pem|Base64|Value)?\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAINTEXT_SECRET_CALL = Pattern.compile(
        "\\.\\s*(?:decrypt|decryptForWorkspace|decryptOidcClientSecret|decryptSamlSpPrivateKey|"
            + "decryptCredential)\\s*\\(|\\.\\s*get\\s*\\(\\s*SecretPurpose\\.",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_STORE_VARIABLE = Pattern.compile(
        "\\bSecretStore\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern SECRET_STORE_CLASS = Pattern.compile("\\bclass\\s+SecretStore\\b");
    private static final Pattern LOG_AUDIT_EXCEPTION_STATEMENT =
        Pattern.compile("\\.\\s*(?:trace|debug|info|warn|error|record[A-Za-z0-9_$]*|singleChange)\\s*\\(|"
            + "\\.\\s*at(?:Trace|Debug|Info|Warn|Error)\\s*\\(\\s*\\).*?\\.\\s*log\\s*\\(|"
            + "\\b[A-Za-z_$][A-Za-z0-9_$.]*\\s*::\\s*(?:trace|debug|info|warn|error)\\b|"
            + "\\bSystem\\s*\\.\\s*(?:out|err)\\s*\\.\\s*(?:print|println|printf)\\s*\\(|"
            + "\\bnew\\s+[A-Za-z0-9_$.]*(?:Exception|Error)\\s*\\(", Pattern.DOTALL);
    private static final Pattern LOCAL_ASSIGNMENT = Pattern.compile(
        "(?:^|[;{}])\\s*((?:(?:final\\s+)?[A-Za-z_$][A-Za-z0-9_$.<>,?\\[\\] ]*\\s+)?)"
            + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(.+?)\\s*;\\s*$",
        Pattern.DOTALL);
    private static final Pattern LOCAL_DECLARATION = Pattern.compile(
        "(?:^|[;{}])\\s*(?:final\\s+)?[A-Za-z_$][A-Za-z0-9_$.<>,?\\[\\] ]*\\s+"
            + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*;\\s*$",
        Pattern.DOTALL);
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern SECRET_VALUE_IDENTIFIER = Pattern.compile(
        "(?:raw|verification|reset|access|session|api|bearer)Token|"
            + "(?:current|new|raw)?password(?:Value|Copy|Text|Plaintext)?|passphrase|clientSecret|privateKey|"
            + "secretAccessKey|apiKey|hmacSecret|serviceAccountJson|plaintext|secretValue|ciphertext|"
            + "encryptedDataKey",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_PAYLOAD_FIELD =
        Pattern.compile("\\b(?:private|protected|public)\\s+"
            + "(?:(?:static|final|transient|volatile)\\s+)*[^=;()]+?\\s+"
            + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=|;)");
    private static final Pattern RESPONSE_PAYLOAD_DECLARATION = Pattern.compile(
        "\\b(?:private|protected|public)\\s+[^;{}]+;", Pattern.DOTALL);
    private static final Pattern JAVA_RECORD_DECLARATION =
        Pattern.compile("\\brecord\\s+[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*<[^>{}]+>)?\\s*\\(");
    private static final Pattern JAVA_IDENTIFIER_AT_END =
        Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*$");
    private static final Pattern RESPONSE_PAYLOAD_GETTER = Pattern.compile(
        "\\bpublic\\s+[^;{}()]+?\\s+(?:get|is)([A-Z][A-Za-z0-9_$]*)\\s*\\(\\s*\\)");
    private static final Pattern EFFECTIVE_JSON_IGNORE = Pattern.compile(
        "@(?:[A-Za-z0-9_$.]+\\.)?JsonIgnore\\b(?:\\s*\\(\\s*(?:value\\s*=\\s*)?true\\s*\\))?"
            + "(?!\\s*\\()", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_SERIALIZED_NAME = Pattern.compile(
        "@(?:[A-Za-z0-9_$.]+\\.)?(?:JsonProperty|JsonGetter)\\s*\\(\\s*"
            + "(?:\"([^\"]+)\"|[^)]*\\bvalue\\s*=\\s*\"([^\"]+)\"[^)]*)\\)");
    private static final Pattern JSON_WRITE_ONLY = Pattern.compile(
        "@(?:[A-Za-z0-9_$.]+\\.)?JsonProperty\\s*\\([^)]*\\baccess\\s*=\\s*"
            + "(?:[A-Za-z0-9_$.]+\\.)?WRITE_ONLY\\b", Pattern.DOTALL);
    private static final Pattern JSON_ANNOTATED_GETTER = Pattern.compile(
        "@(?:[A-Za-z0-9_$.]+\\.)?(?:JsonGetter(?:\\s*\\([^)]*\\))?|JsonProperty(?:\\s*\\([^)]*\\))?)"
            + "\\s*(?:@[A-Za-z0-9_$.]+(?:\\([^)]*\\))?\\s*)*"
            + "(?:(?:public|protected|private|final|synchronized|native|abstract|default)\\s+)*"
            + "[^;{}()]+\\s+"
            + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(\\s*\\)", Pattern.DOTALL);
    private static final Pattern JSON_ANNOTATED_FIELD = Pattern.compile(
        "@(?:[A-Za-z0-9_$.]+\\.)?JsonProperty(?:\\s*\\([^)]*\\))?"
            + "\\s*(?:@[A-Za-z0-9_$.]+(?:\\([^)]*\\))?\\s*)*"
            + "(?:(?:public|protected|private|final|transient|volatile)\\s+)*"
            + "[^;{}()=]+?\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=|;)",
        Pattern.DOTALL);
    private static final Pattern JSON_STRING_VALUE = Pattern.compile(
        ":\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern MARKUP_TEXT_NODE = Pattern.compile(">([^<>]+)<");
    private static final Pattern MARKUP_ATTRIBUTE_VALUE = Pattern.compile(
        "\\b(?:title|aria-label|content|alt|label|description|subject)\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVA_STRING_LITERAL = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern RESPONSE_PAYLOAD_SECRET_FIELD = Pattern.compile(
        "password|passphrase|token|clientSecret|privateKey|secretAccessKey|apiKey|hmacSecret|plaintext|"
            + "serviceAccountJson|ciphertext|encryptedDataKey|encrypted_data_key|secretId|secretReference|"
            + "secretRef|secretValue",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CIPHER_API_REFERENCE =
        Pattern.compile("\\bjavax\\.crypto\\.Cipher\\b|\\bCipher\\s*\\.|"
            + "\\bCipher\\s+[A-Za-z_$][A-Za-z0-9_$]*\\b");
    private static final Pattern JAVA_TYPE_HIERARCHY = Pattern.compile(
        "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
            + "(?:\\s*<[^>{}]+>)?\\s+(?:extends|implements)\\s+([^{}]+)\\{");
    private static final Pattern JAVA_TYPE_OPEN = Pattern.compile(
        "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
            + "(?:\\s*<[^>{}]+>)?[^;{}]*\\{");
    private static final Pattern JAVA_PARENT_TYPE = Pattern.compile(
        "((?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Z][A-Za-z0-9_$]*)");
    private static final Pattern JAVA_PACKAGE = Pattern.compile("\\bpackage\\s+([a-zA-Z_$][A-Za-z0-9_$.]*)\\s*;");
    private static final Pattern JAVA_IMPORT = Pattern.compile(
        "\\bimport\\s+(?!static\\b)([a-zA-Z_$][A-Za-z0-9_$.]*\\.[A-Z][A-Za-z0-9_$]*)\\s*;");
    private static final Pattern ANONYMOUS_TYPE_IMPLEMENTATION = Pattern.compile(
        "\\bnew\\s+((?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Z][A-Za-z0-9_$]*)"
            + "(?:\\s*<[^;{}()]*>)?\\s*\\([^;{}]*\\)\\s*\\{");
    private static final Pattern MYBATIS_TYPE_HANDLER_ANNOTATION =
        Pattern.compile("@(?:[A-Za-z0-9_$.]+\\.)?(?:MappedTypes|MappedJdbcTypes)\\b");
    private static final Pattern JPA_CONVERTER_REFERENCE =
        Pattern.compile("\\bAttributeConverter\\b|"
            + "@(?:[A-Za-z0-9_$.]+\\.)?(?:Convert|ColumnTransformer)\\b");
    private static final Pattern TRANSPARENT_ENCRYPTION_REFERENCE =
        Pattern.compile("\\b(?:javax\\.crypto\\.)?Cipher\\b|\\.\\s*(?:encrypt|decrypt)\\s*\\(|"
            + "\\bSecretKeySpec\\b|secret:v1", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNQUALIFIED_ENCRYPTION_CALL = Pattern.compile(
        "(?<![A-Za-z0-9_$.])(?:encrypt|decrypt)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern MYBATIS_XML_CRYPTO_TRANSFORM = Pattern.compile(
        "\\b(?:[A-Z0-9_$]+_)?(?:ENCRYPT|DECRYPT)\\s*\\(|\\.\\s*(?:encrypt|decrypt)\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDENTIAL_STORAGE_REFERENCE = Pattern.compile(
        "\\b(SecretStoreCrypto|SecretStore|SecretCipher|SsoSecretCipher|AiProviderSecretCipher|UserProviderSecretCipher|AesGcm)\\b");
    private static final Pattern SECRET_PURPOSE_REFERENCE =
        Pattern.compile("\\bSecretPurpose\\.([A-Z][A-Z0-9_]*)\\b");
    private static final Pattern SECRET_PURPOSE_DECLARATION = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\b");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
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
        "sso_connection.saml_sp_private_key_enc",
        "delivery_provider_config.webhook_secret_ref");

    private static final Set<String> EXPECTED_APPROVED_CIPHER_SITES = Set.of(
        "ooo/klae/connex/backend/secrets/SecretStoreCrypto.java",
        "ooo/klae/connex/backend/sso/AesGcm.java",
        "ooo/klae/connex/backend/mail/SecretCipher.java");

    private static final Map<String, Integer> APPROVED_CREDENTIAL_STORAGE_REFERENCES = Map.ofEntries(
        Map.entry("ooo/klae/connex/backend/ai/AiProviderSecretCipher.java#SecretStore", 1),
        Map.entry("ooo/klae/connex/backend/config/SecretStoreStartupValidator.java#SecretStore", 1),
        Map.entry("ooo/klae/connex/backend/delivery/ConnectorSecretCipher.java#SecretStore", 1),
        Map.entry("ooo/klae/connex/backend/delivery/DeliveryProviderSecretCipher.java#SecretStore", 1),
        Map.entry("ooo/klae/connex/backend/mail/MailConfigResolver.java#SecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/mail/SecretCipher.java#SecretStore", 2),
        Map.entry("ooo/klae/connex/backend/secrets/LegacySecretRewrapRunner.java#SecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/secrets/LegacySecretRewrapRunner.java#SsoSecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/secrets/SecretStore.java#SecretStoreCrypto", 9),
        Map.entry("ooo/klae/connex/backend/secrets/SecretStoreLifecycleService.java#SecretStore", 1),
        Map.entry("ooo/klae/connex/backend/secrets/SecretStoreLifecycleService.java#SecretStoreCrypto", 4),
        Map.entry("ooo/klae/connex/backend/secrets/SecretStoreRewrapRunner.java#SecretStore", 1),
        Map.entry("ooo/klae/connex/backend/services/AiProviderConfigService.java#AiProviderSecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/services/SsoConnectionService.java#SsoSecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/services/WorkspaceMailConfigService.java#SecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/sso/DbClientRegistrationRepository.java#SsoSecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/sso/DbRelyingPartyRegistrationRepository.java#SsoSecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/sso/SsoSecretCipher.java#AesGcm", 3),
        Map.entry("ooo/klae/connex/backend/sso/SsoSecretCipher.java#SecretStore", 2),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/UserProviderSecretCipher.java#SecretStore", 1),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/nativeflow/NativeConnectPkceSecretCipher.java#SecretStore", 1),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/ProviderConnectionLifecyclePersistence.java#UserProviderSecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/ProviderConnectionLifecycleService.java#UserProviderSecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/ProviderCredentialPersistence.java#UserProviderSecretCipher", 1),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/ProviderCredentialService.java#UserProviderSecretCipher", 1));

    private static final Map<String, Integer> APPROVED_SECRET_PURPOSE_REFERENCES = Map.ofEntries(
        Map.entry("ooo/klae/connex/backend/ai/AiProviderSecretCipher.java#ORG_AI_PROVIDER_CREDENTIAL", 3),
        Map.entry("ooo/klae/connex/backend/delivery/ConnectorSecretCipher.java#WORKSPACE_CONNECTOR_CREDENTIAL", 3),
        Map.entry("ooo/klae/connex/backend/delivery/DeliveryProviderSecretCipher.java#WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL", 3),
        Map.entry("ooo/klae/connex/backend/delivery/DeliveryProviderSecretCipher.java#WORKSPACE_DELIVERY_WEBHOOK_SECRET", 3),
        Map.entry("ooo/klae/connex/backend/mail/SecretCipher.java#WORKSPACE_SMTP_PASSWORD", 3),
        Map.entry("ooo/klae/connex/backend/sso/SsoSecretCipher.java#ORG_SSO_OIDC_CLIENT_SECRET", 3),
        Map.entry("ooo/klae/connex/backend/sso/SsoSecretCipher.java#ORG_SSO_SAML_SP_PRIVATE_KEY", 3),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/UserProviderSecretCipher.java#USER_PROVIDER_GOOGLE_TOKEN", 1),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/UserProviderSecretCipher.java#USER_PROVIDER_MICROSOFT_TOKEN", 1),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/nativeflow/NativeConnectPkceSecretCipher.java#USER_PROVIDER_PKCE_VERIFIER", 1),
        Map.entry("ooo/klae/connex/backend/connectedaccounts/nativeflow/NativeConnectPkceSecretCipher.java#USER_PROVIDER_MICROSOFT_PKCE_VERIFIER", 1));

    private static final Set<String> APPROVED_SECRET_PURPOSES = Set.of(
        "WORKSPACE_SMTP_PASSWORD",
        "WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL",
        "WORKSPACE_DELIVERY_WEBHOOK_SECRET",
        "WORKSPACE_CONNECTOR_CREDENTIAL",
        "ORG_SSO_OIDC_CLIENT_SECRET",
        "ORG_SSO_SAML_SP_PRIVATE_KEY",
        "ORG_AI_PROVIDER_CREDENTIAL",
        "USER_PROVIDER_GOOGLE_TOKEN",
        "USER_PROVIDER_MICROSOFT_TOKEN",
        "USER_PROVIDER_PKCE_VERIFIER",
        "USER_PROVIDER_MICROSOFT_PKCE_VERIFIER");

    private static final Set<String> APPROVED_SECRET_INPUT_FIELDS = Set.of(
        "ooo/klae/connex/backend/dto/AiProviderConfigRequest.java#secretAccessKey",
        "ooo/klae/connex/backend/dto/AiProviderConfigRequest.java#sessionToken",
        "ooo/klae/connex/backend/dto/AiProviderConfigRequest.java#apiKey",
        "ooo/klae/connex/backend/dto/AiProviderConfigRequest.java#serviceAccountJson",
        "ooo/klae/connex/backend/dto/BusinessCardCompanyAction.java#duplicateReviewToken",
        "ooo/klae/connex/backend/dto/BusinessCardContactRequest.java#duplicateReviewToken",
        "ooo/klae/connex/backend/dto/BusinessCardPersonAction.java#duplicateReviewToken",
        "ooo/klae/connex/backend/dto/ConnectorConfigRequest.java#apiKey",
        "ooo/klae/connex/backend/dto/DealDuplicatePreflightRequest.java#reviewToken",
        "ooo/klae/connex/backend/dto/DeliveryProviderConfigRequest.java#apiKey",
        "ooo/klae/connex/backend/dto/EmailChangeRequestDto.java#currentPassword",
        "ooo/klae/connex/backend/dto/LoginDto.java#password",
        "ooo/klae/connex/backend/dto/MailConfigRequest.java#password",
        "ooo/klae/connex/backend/dto/OneTimeLinkExchangeRequest.java#token",
        "ooo/klae/connex/backend/dto/PasskeyRegistrationOptionsRequest.java#currentPassword",
        "ooo/klae/connex/backend/dto/ProviderCaptureReviewRequest.java#duplicateReviewToken",
        "ooo/klae/connex/backend/dto/RecordCommentCreateRequest.java#clientToken",
        "ooo/klae/connex/backend/dto/RecordCommentCreateThreadRequest.java#clientToken",
        "ooo/klae/connex/backend/dto/RegisterDto.java#password",
        "ooo/klae/connex/backend/dto/ResetPasswordRequest.java#newPassword",
        "ooo/klae/connex/backend/dto/SsoConnectionRequest.java#oidcClientSecret",
        "ooo/klae/connex/backend/dto/SsoLinkConfirmRequest.java#password",
        "ooo/klae/connex/backend/dto/WorkflowManualConfirmRequest.java#scopeToken");

    private static final Set<String> APPROVED_SECRET_RESPONSE_FIELDS = Set.of(
        "ooo/klae/connex/backend/dto/CsrfBootstrapDto.java#token",
        "ooo/klae/connex/backend/dto/CompanyDto.java#duplicateReviewToken",
        "ooo/klae/connex/backend/dto/DealDto.java#duplicateReviewToken",
        "ooo/klae/connex/backend/dto/DeliveryWebhookTokenDto.java#token",
        "ooo/klae/connex/backend/dto/DuplicatePreflightResponse.java#reviewToken",
        "ooo/klae/connex/backend/dto/InviteDto.java#token",
        "ooo/klae/connex/backend/dto/InviteLinkDto.java#token",
        "ooo/klae/connex/backend/dto/MailConfigDto.java#hasPassword",
        "ooo/klae/connex/backend/dto/PasskeyRegistrationRequirementsDto.java#currentPasswordRequired",
        "ooo/klae/connex/backend/dto/PersonDto.java#duplicateReviewToken",
        "ooo/klae/connex/backend/dto/SecretStoreSecretDiagnosticDto.java#secretId",
        "ooo/klae/connex/backend/dto/SsoConnectionDto.java#hasClientSecret",
        "ooo/klae/connex/backend/dto/WorkflowManualPreparationDto.java#scopeToken");

    private static final Map<String, ApprovedSecretSink> APPROVED_SECRET_SINKS = Map.of();

    private static final Set<String> CORE_CRM_TABLES = Set.of(
        "person",
        "person_identity",
        "person_share",
        "person_tag",
        "person_employment",
        "person_edge",
        "company",
        "company_identity",
        "company_share",
        "company_tag",
        "identity_collision",
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
            violations.addAll(unsupportedDocClaimViolations(repoRoot(), path));
        }

        assertTrue(violations.isEmpty(),
            "Unsupported hosted-SaaS encryption claims must be denied or explicitly qualified to "
                + "customer-operated/on-prem deployments per docs/ENCRYPTION_GUARANTEE_MATRIX.md: " + violations);
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
        violations.addAll(responsePayloadSecretFieldViolations());

        assertTrue(violations.isEmpty(),
            "Secret plaintext, ciphertext, wrapped data keys, and encrypted references must not be logged, "
                + "placed in exception messages, or exposed from response DTOs: " + violations);
    }

    @Test
    void app_level_ciphers_are_confined_to_explicit_approved_files() throws Exception {
        List<String> violations = new ArrayList<>();
        Set<String> seenApproved = new LinkedHashSet<>();
        Path main = repoRoot().resolve("backend/src/main/java");
        List<Path> javaFiles = javaSourceFiles(main);
        assertTrue(javaFiles.size() >= 100,
            "Only found " + javaFiles.size() + " backend Java files; the cipher scan is likely misconfigured.");
        violations.addAll(cipherReferenceViolations(main, javaFiles, EXPECTED_APPROVED_CIPHER_SITES,
            seenApproved));

        List<String> missingApproved = EXPECTED_APPROVED_CIPHER_SITES.stream()
            .filter(site -> !seenApproved.contains(site))
            .sorted()
            .toList();
        assertTrue(missingApproved.isEmpty(),
            "Expected approved Cipher sites were not scanned as Cipher users: " + missingApproved);
        assertTrue(violations.isEmpty(),
            "App-level Cipher use is confined to explicit approved files. Searchable CRM data must follow "
                + "docs/ENCRYPTION_GUARANTEE_MATRIX.md and #375 instead of adding service, mapper, bean, or provider "
                + "encryption: " + violations);
    }

    @Test
    void credential_storage_is_confined_to_explicit_approved_files_and_purposes() throws Exception {
        Path main = repoRoot().resolve("backend/src/main/java");
        List<String> violations = credentialStorageViolations(main, javaSourceFiles(main));

        assertTrue(violations.isEmpty(),
            "SecretStore, SecretPurpose, and credential-encryption facades are only for explicitly approved "
                + "never-searched credential flows. Searchable CRM storage must not reuse them: " + violations);

        Path purposeFile = main.resolve("ooo/klae/connex/backend/secrets/SecretPurpose.java");
        Set<String> actualPurposes = secretPurposeDeclarations(purposeFile);
        assertEquals(APPROVED_SECRET_PURPOSES, actualPurposes,
            "SecretPurpose is a closed catalog of reviewed never-searched credentials; update the explicit "
                + "catalog only after confirming the new purpose is not searchable CRM storage.");
    }

    @Test
    void mybatis_xml_does_not_transform_fields_with_application_crypto() throws Exception {
        Path mappers = repoRoot().resolve("backend/src/main/resources/mappers");
        List<Path> mapperFiles;
        try (Stream<Path> files = Files.walk(mappers)) {
            mapperFiles = files
                .filter(path -> path.getFileName().toString().endsWith(".xml"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
        assertTrue(mapperFiles.size() >= 40,
            "Only found " + mapperFiles.size() + " MyBatis XML files; the crypto-transform scan is misconfigured.");
        List<String> violations = mybatisXmlCryptoTransformViolations(mappers, mapperFiles);
        assertTrue(violations.isEmpty(),
            "MyBatis XML must not apply AES/encrypt/decrypt transforms to database fields. Searchable CRM fields "
                + "must use storage/database encryption: " + violations);
    }

    @Test
    void mybatis_type_handlers_do_not_transparently_encrypt() throws Exception {
        Path main = repoRoot().resolve("backend/src/main/java");
        List<Path> javaFiles = javaSourceFiles(main);
        assertTrue(javaFiles.size() >= 100,
            "Only found " + javaFiles.size() + " backend Java files; the type-handler scan is likely misconfigured.");
        List<String> violations = transparentEncryptionHandlerViolations(main, javaFiles);

        assertTrue(violations.isEmpty(),
            "MyBatis type handlers and JPA-style converters must not transparently encrypt database columns. "
                + "Searchable CRM fields must use storage/database encryption per docs/ENCRYPTION_GUARANTEE_MATRIX.md "
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

    @Test
    void documentation_fixture_binds_exceptions_to_the_matched_statement(@TempDir Path fixtureRoot) throws Exception {
        Path adjacent = writeFixture(fixtureRoot, "claims.md", """
            Hosted Connex is E2EE.
            Do not say hosted Connex is E2EE.
            """);
        Path wrapped = writeFixture(fixtureRoot, "wrapped.md", """
            Hosted storage is not
            end-to-end encryption or zero-knowledge encryption.
            """);
        Path table = writeFixture(fixtureRoot, "table.md", """
            | Claim | Separate policy |
            | Connex is E2EE. | Hosted Connex is not E2EE. |
            | Is Connex E2EE? | No for hosted SaaS. |
            """);
        Path notOnly = writeFixture(fixtureRoot, "not-only.md", """
            Not only is Connex E2EE, it is convenient.
            """);
        Path json = writeFixture(fixtureRoot, "claims.json", """
            {"policy":"Do not say E2EE","marketing":"Connex is E2EE"}
            """);
        Path html = writeFixture(fixtureRoot, "claims.html", """
            <p>Do not say E2EE</p><p>Connex is E2EE</p>
            """);
        Path misboundNegation = writeFixture(fixtureRoot, "misbound-negation.md", """
            Hosted Connex is E2EE, but another deployment is not zero-knowledge.
            """);
        Path misboundOnPrem = writeFixture(fixtureRoot, "misbound-on-prem.md", """
            Hosted Connex is E2EE, while customer-operated/on-prem only deployments control their keys.
            """);
        Path coordinatedClaims = writeFixture(fixtureRoot, "coordinated-claims.md", """
            This deployment is not E2EE and hosted Connex is zero-knowledge.
            Customer-operated/on-prem only deployments may be E2EE and hosted Connex is zero-knowledge.
            """);
        Path clausePolicy = writeFixture(fixtureRoot, "clause-policy.md", """
            Do not say legacy mode is E2EE, and hosted Connex is zero-knowledge.
            """);
        Path misplacedQualifier = writeFixture(fixtureRoot, "misplaced-qualifier.md", """
            Hosted Connex is E2EE because customer-operated/on-prem only deployments also exist.
            """);
        Path validPostfixNegation = writeFixture(fixtureRoot, "postfix-negation.md", """
            E2EE is not available for hosted Connex.
            """);
        Path attributedHtml = writeFixture(fixtureRoot, "attributed.html", """
            <p title="Hosted Connex is E2EE">Safe copy</p>
            """);
        Path email = writeFixture(fixtureRoot,
            "backend/src/main/resources/templates/emails/claim.html", """
                <p>Hosted Connex is zero-knowledge.</p>
                """);
        Path emailSubject = writeFixture(fixtureRoot,
            "backend/src/main/java/ooo/klae/connex/backend/services/ClaimEmailService.java", """
                class ClaimEmailService {
                    void send(String email, String body) {
                        MailMessage.html(email, "Hosted Connex is E2EE", body);
                    }
                }
                """);

        List<String> adjacentViolations = unsupportedDocClaimViolations(fixtureRoot, adjacent);
        assertEquals(1, adjacentViolations.size());
        assertTrue(adjacentViolations.getFirst().contains("claims.md:1"));
        assertTrue(unsupportedDocClaimViolations(fixtureRoot, wrapped).isEmpty());
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, table).size());
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, notOnly).size());
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, json).size());
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, html).size());
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, misboundNegation).size());
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, misboundOnPrem).size());
        assertEquals(2, unsupportedDocClaimViolations(fixtureRoot, coordinatedClaims).size());
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, clausePolicy).size());
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, misplacedQualifier).size());
        assertTrue(unsupportedDocClaimViolations(fixtureRoot, validPostfixNegation).isEmpty());
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, attributedHtml).size());
        assertTrue(customerFacingTextFiles(fixtureRoot).contains(email));
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, email).size());
        assertTrue(customerFacingTextFiles(fixtureRoot).contains(emailSubject));
        assertEquals(1, unsupportedDocClaimViolations(fixtureRoot, emailSubject).size());
    }

    @Test
    void java_fixture_scans_controller_audits_and_plaintext_secret_sinks(@TempDir Path fixtureRoot)
            throws Exception {
        Path controller = writeFixture(fixtureRoot, "controllers/FixtureController.java", """
            class FixtureController {
                void unsafe(Request request) {
                    auditService.singleChange("apiToken", null, request.getToken());
                    auditService.singleChange("label", null, request.getLabel());
                }
            }
            """);
        Path sinks = writeFixture(fixtureRoot, "services/FixtureService.java", """
            class FixtureService {
                private final SecretStore vault;
                void unsafe(Request request, Config config, SecretPurpose purpose) {
                    logger.warn("password {}", request.getPassword());
                    throw new IllegalStateException(request.getApiKey());
                    securityAudit.record("credential", request.getClientSecret());
                    auditService.record("credential", config.password());
                    telemetry.error("credential {}", vault.get(purpose, 1, ref));
                    events.record("credential", cipher.decryptCredential(1, ref));
                    audit.recordFailureScoped("credential", request.getPassword());
                    String password = request.getPassword();
                    String passwordCopy = password;
                    logger.info("credential {}", passwordCopy);
                    String decrypted = cipher.decryptCredential(1, ref);
                    throw new IllegalArgumentException(decrypted);
                    String stored = vault.get(purpose, 1, ref);
                    securityAudit.record("credential", stored);
                    String transformed = String.valueOf(password);
                    logger.warn("credential {}", transformed);
                    String branchValue = "redacted";
                    if (request.isEnabled()) {
                        branchValue = request.getPassword();
                    }
                    logger.info("credential {}", branchValue);
                    log.atInfo().log("credential {}", password);
                    List.of(password).forEach(logger::info);
                    passwordEncoder.encode(request.getPassword());
                }
            }
            """);
        Path approvedSink = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/services/LoggingPasswordResetEmailService.java", """
                class LoggingPasswordResetEmailService {
                    void sendResetEmail(String rawToken) {
                        String link = builder.path("/auth/reset-password")
                            .queryParam("token", rawToken).toString();
                        log.info("Password reset link for userId {} (dev link logging enabled): {}",
                            user.getId(), link);
                    }
                }
                """);
        Path disguisedSink = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/services/LoggingEmailChangeEmailService.java", """
                class LoggingEmailChangeEmailService {
                    void send(String password) {
                        String link = password;
                        log.info("Email-change verification link for userId {} (dev link logging enabled): {}",
                            user.getUsername(), link);
                    }
                }
                """);
        Path extendedSink = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/services/LoggingRegistrationVerificationEmailService.java", """
                class LoggingRegistrationVerificationEmailService {
                    void send(String rawToken) {
                        String link = builder.path("/auth/confirm-email")
                            .queryParam("token", rawToken).toString();
                        log.info("Registration verification link for userId {} (dev link logging enabled): {} extra",
                            user.getId(), link);
                    }
                }
                """);

        List<String> auditViolations = singleChangeFieldViolations(fixtureRoot, List.of(controller));
        assertEquals(1, auditViolations.size());
        assertTrue(auditViolations.getFirst().contains("apiToken"));
        List<String> sinkViolations = secretAccessorSinkViolations(fixtureRoot,
            List.of(sinks, approvedSink, disguisedSink, extendedSink));
        assertEquals(17, sinkViolations.size());
        assertTrue(sinkViolations.stream().anyMatch(value -> value.contains("passwordCopy")));
        assertTrue(sinkViolations.stream().anyMatch(value -> value.contains("transformed")));
        assertTrue(sinkViolations.stream().anyMatch(value -> value.contains("branchValue")));
        assertTrue(sinkViolations.stream().anyMatch(value -> value.contains("atInfo")));
        assertTrue(sinkViolations.stream().anyMatch(value -> value.contains("logger::info")));
        assertTrue(sinkViolations.stream().anyMatch(value -> value.contains("IllegalArgumentException(decrypted)")));
        assertTrue(sinkViolations.stream().anyMatch(value -> value.contains("credential\", stored")));
        assertTrue(sinkViolations.stream().anyMatch(value -> value.contains("LoggingPasswordResetEmailService")));
        assertTrue(sinkViolations.stream().anyMatch(value -> value.contains("LoggingEmailChangeEmailService")));
        assertTrue(sinkViolations.stream()
            .anyMatch(value -> value.contains("LoggingRegistrationVerificationEmailService")));
    }

    @Test
    void response_fixture_scans_non_dto_records_and_exact_secret_metadata(@TempDir Path fixtureRoot)
            throws Exception {
        Path ordinary = writeFixture(fixtureRoot, "ooo/klae/connex/backend/dto/ExportResult.java", """
            class ExportResult {
                private String secretValue;
                private long secretId;
            }
            """);
        Path record = writeFixture(fixtureRoot, "ooo/klae/connex/backend/dto/ExportRecord.java", """
            record ExportRecord(String clientSecret, String label) {}
            """);
        Path aliasRecord = writeFixture(fixtureRoot, "ooo/klae/connex/backend/dto/AliasRecord.java", """
            record AliasRecord(
                @JsonProperty(access = JsonProperty.Access.READ_ONLY, value = "clientSecret") String value) {}
            """);
        Path ignored = writeFixture(fixtureRoot, "ooo/klae/connex/backend/beans/SafeUser.java", """
            class SafeUser {
                @JsonIgnore
                private String passwordHash;
            }
            """);
        Path diagnostic = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/dto/SecretStoreSecretDiagnosticDto.java", """
                class SecretStoreSecretDiagnosticDto {
                    private long secretId;
                }
                """);
        Path exposed = writeFixture(fixtureRoot, "ooo/klae/connex/backend/beans/ExposedPayload.java", """
            class ExposedPayload {
                @JsonIgnore(false)
                private String password;
                private String passphrase, label;
                public String apiKey;
                public String getPrivateKey() {
                    return "value";
                }
                @JsonGetter("password")
                public String value() {
                    return "value";
                }
                @JsonGetter
                public String apiToken() {
                    return "value";
                }
                @JsonGetter()
                public String clientSecret() {
                    return "value";
                }
                @JsonProperty
                public String privateKey() {
                    return "value";
                }
                @JsonProperty("displayName")
                public String hmacSecret() {
                    return "value";
                }
                @JsonGetter("sessionToken")
                private String internalValue() {
                    return "value";
                }
                @JsonProperty("apiToken")
                String packageValue;
            }
            """);
        Path input = writeFixture(fixtureRoot, "ooo/klae/connex/backend/dto/LoginDto.java", """
            class LoginDto {
                private String password;
            }
            """);

        List<String> violations = responsePayloadSecretFieldViolations(fixtureRoot,
            List.of(ordinary, record, aliasRecord, ignored, diagnostic, exposed), Set.of(diagnostic));
        assertEquals(15, violations.size());
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExportResult.java")
            && value.contains("secretValue")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExportResult.java")
            && value.contains("secretId")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExportRecord.java")
            && value.contains("clientSecret")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("AliasRecord.java")
            && value.contains("clientSecret")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExposedPayload.java")
            && value.contains("password")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExposedPayload.java")
            && value.contains("passphrase")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExposedPayload.java")
            && value.contains("apiKey")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExposedPayload.java")
            && value.contains("privateKey")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExposedPayload.java")
            && value.contains("apiToken")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExposedPayload.java")
            && value.contains("clientSecret")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExposedPayload.java")
            && value.contains("hmacSecret")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExposedPayload.java")
            && value.contains("sessionToken")));
        assertTrue(violations.stream().anyMatch(value -> value.contains("ExposedPayload.java")
            && value.contains("apiToken")));
        assertTrue(responsePayloadSecretFieldViolations(fixtureRoot, List.of(input), Set.of()).isEmpty());
        List<String> returnedInput = responsePayloadSecretFieldViolations(fixtureRoot, List.of(input), Set.of(input));
        assertEquals(1, returnedInput.size());
        assertTrue(returnedInput.getFirst().contains("LoginDto.java")
            && returnedInput.getFirst().contains("password"));
    }

    @Test
    void storage_fixture_rejects_naming_and_package_bypasses(@TempDir Path fixtureRoot) throws Exception {
        Path crmService = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/services/PersonSearchService.java", """
                class PersonSearchService {
                    private final SecretStore secretStore;
                    String store(int workspaceId, String email) {
                        return secretStore.put(SecretPurpose.WORKSPACE_SMTP_PASSWORD, workspaceId, email);
                    }
                }
                """);
        Path handler = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/mappers/OpaqueJdbcCodec.java", """
                class OpaqueJdbcCodec implements TypeHandler<String> {
                    String encode(String value) {
                        return Cipher.getInstance("AES/GCM/NoPadding").toString();
                    }
                }
                """);
        Path anonymousHandler = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/mappers/HandlerRegistry.java", """
                class HandlerRegistry {
                    TypeHandler<String> handler = new TypeHandler<String>() {
                        String encode(String value) {
                            return Cipher.getInstance("AES/GCM/NoPadding").toString();
                        }
                    };
                }
                """);
        Path handlerAlias = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/mappers/CryptoHandler.java", """
                interface CryptoHandler<T> extends TypeHandler<T> {}
                """);
        Path unrelatedAlias = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/unrelated/CryptoHandler.java", """
                interface CryptoHandler<T> {}
                """);
        Path indirectHandler = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/mappers/IndirectCodec.java", """
                class IndirectCodec implements CryptoHandler<String> {
                    String encode(String value) {
                        return crypto.encrypt(value);
                    }
                }
                """);
        Path anonymousAlias = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/mappers/AliasHandlerRegistry.java", """
                class AliasHandlerRegistry {
                    CryptoHandler<String> handler = new CryptoHandler<String>() {
                        String encode(String value) {
                            return crypto.encrypt(value);
                        }
                    };
                }
                """);
        Path unrelatedIndirect = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/unrelated/IndirectCodec.java", """
                class IndirectCodec implements CryptoHandler<String> {
                    String encode(String value) {
                        return crypto.encrypt(value);
                    }
                }
                """);
        Path nestedAlias = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/mappers/HandlerTypes.java", """
                class HandlerTypes {
                    interface CryptoHandler<T> extends TypeHandler<T> {}
                }
                """);
        Path nestedIndirect = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/mappers/NestedIndirectCodec.java", """
                class NestedIndirectCodec implements HandlerTypes.CryptoHandler<String> {
                    String encode(String value) {
                        return crypto.encrypt(value);
                    }
                }
                """);
        Path staticImportHandler = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/mappers/StaticImportCodec.java", """
                import static example.Crypto.encrypt;
                class StaticImportCodec implements TypeHandler<String> {
                    String encode(String value) {
                        return encrypt(value);
                    }
                }
                """);
        Path facadeReuse = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/services/PersonCryptoService.java", """
                class PersonCryptoService {
                    private final SecretCipher secretCipher;
                }
                """);
        Path lowLevelReuse = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/services/PersonFieldCryptoService.java", """
                class PersonFieldCryptoService {
                    private final SecretStoreCrypto crypto;
                }
                """);
        Path packagePeer = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/secrets/PersonFieldCipher.java", """
                class PersonFieldCipher {
                    Cipher cipher;
                }
                """);
        Path approvedCipher = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/secrets/SecretStoreCrypto.java", """
                class SecretStoreCrypto {
                    Cipher cipher;
                }
                """);
        Path mapper = writeFixture(fixtureRoot, "PersonMapper.xml", """
            <mapper namespace="PersonMapper">
                <select id="search">SELECT AES_DECRYPT(email, #{key}) FROM person</select>
            </mapper>
            """);
        Path purpose = writeFixture(fixtureRoot, "SecretPurpose.java", """
            enum SecretPurpose {
                WORKSPACE_SMTP_PASSWORD,
                PERSON_EMAIL;
            }
            """);
        Path staleApproved = writeFixture(fixtureRoot,
            "ooo/klae/connex/backend/ai/AiProviderSecretCipher.java", """
                class AiProviderSecretCipher {
                    String label = "SecretStore";
                }
                """);

        assertEquals(4, credentialStorageViolations(fixtureRoot,
            List.of(crmService, facadeReuse, lowLevelReuse)).size());
        List<String> staleViolations = credentialStorageViolations(fixtureRoot, List.of(staleApproved));
        assertTrue(staleViolations.stream().anyMatch(value -> value.contains("expected")
            && value.contains("#SecretStore")));
        List<String> handlerViolations = transparentEncryptionHandlerViolations(fixtureRoot,
            List.of(handler, anonymousHandler, handlerAlias, unrelatedAlias, indirectHandler, anonymousAlias,
                unrelatedIndirect, nestedAlias, nestedIndirect, staticImportHandler));
        assertEquals(6, handlerViolations.size());
        assertTrue(handlerViolations.stream().anyMatch(value -> value.endsWith("AliasHandlerRegistry.java")));
        assertTrue(handlerViolations.stream().anyMatch(value -> value.endsWith("NestedIndirectCodec.java")));
        assertTrue(handlerViolations.stream().anyMatch(value -> value.endsWith("StaticImportCodec.java")));
        assertTrue(handlerViolations.stream().noneMatch(value -> value.contains("/unrelated/")));
        Set<String> seenApproved = new LinkedHashSet<>();
        assertEquals(1, cipherReferenceViolations(fixtureRoot, List.of(packagePeer, approvedCipher),
            EXPECTED_APPROVED_CIPHER_SITES, seenApproved).size());
        assertTrue(seenApproved.contains("ooo/klae/connex/backend/secrets/SecretStoreCrypto.java"));
        assertEquals(1, mybatisXmlCryptoTransformViolations(fixtureRoot, List.of(mapper)).size());
        assertTrue(secretPurposeDeclarations(purpose).contains("PERSON_EMAIL"));
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

    private List<String> unsupportedDocClaimViolations(Path root, Path path) throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (SourceStatement statement : documentStatements(path, lines)) {
            for (SourceSlice clause : documentClaimClauses(statement.text())) {
                for (SourceSlice claim : unsupportedClaims(clause.text())) {
                    int offset = clause.offset() + claim.offset();
                    int line = statement.line() + newlineCount(statement.text(), offset);
                    violations.add(displayPath(root, path) + ":" + line + ": " + clause.text().strip());
                }
            }
        }
        return violations;
    }

    private List<SourceSlice> documentClaimClauses(String statement) {
        List<SourceSlice> clauses = new ArrayList<>();
        Matcher boundary = DOCUMENT_CLAUSE_BOUNDARY.matcher(statement);
        int start = 0;
        while (boundary.find()) {
            if (!statement.substring(start, boundary.start()).isBlank()) {
                clauses.add(new SourceSlice(start, statement.substring(start, boundary.start())));
            }
            start = boundary.end();
        }
        if (!statement.substring(start).isBlank()) {
            clauses.add(new SourceSlice(start, statement.substring(start)));
        }
        return clauses;
    }

    private List<SourceSlice> unsupportedClaims(String clause) {
        List<SourceSlice> claims = new ArrayList<>();
        Matcher matcher = UNSUPPORTED_DOC_CLAIM.matcher(clause);
        while (matcher.find()) {
            claims.add(new SourceSlice(matcher.start(), matcher.group()));
        }
        List<Boolean> directSupport = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            SourceSlice claim = claims.get(i);
            int contextStart = i == 0
                ? 0
                : claims.get(i - 1).offset() + claims.get(i - 1).text().length();
            int contextEnd = i + 1 == claims.size() ? clause.length() : claims.get(i + 1).offset();
            String context = clause.substring(contextStart, contextEnd);
            String prefix = clause.substring(contextStart, claim.offset());
            String suffix = clause.substring(claim.offset() + claim.text().length(), contextEnd);
            directSupport.add(supportedDocClaimContext(context)
                || supportedCustomerOperatedContext(prefix, suffix));
        }
        List<SourceSlice> unsupported = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            SourceSlice claim = claims.get(i);
            if (!claimListSupported(clause, claims, directSupport, i)) {
                unsupported.add(claim);
            }
        }
        return unsupported;
    }

    private boolean claimListSupported(String clause, List<SourceSlice> claims, List<Boolean> directSupport,
            int claimIndex) {
        int start = claimIndex;
        while (start > 0 && claimsConnectedAsList(clause, claims.get(start - 1), claims.get(start))) {
            start--;
        }
        int end = claimIndex;
        while (end + 1 < claims.size() && claimsConnectedAsList(clause, claims.get(end), claims.get(end + 1))) {
            end++;
        }
        for (int i = start; i <= end; i++) {
            if (directSupport.get(i)) {
                return true;
            }
        }
        return false;
    }

    private boolean claimsConnectedAsList(String clause, SourceSlice first, SourceSlice second) {
        return listConnector(clause.substring(first.offset() + first.text().length(), second.offset()));
    }

    private boolean supportedCustomerOperatedContext(String prefix, String suffix) {
        boolean qualifiedPrefix = CUSTOMER_OPERATED_QUALIFIER.matcher(prefix).find()
            && !HOSTED_DEPLOYMENT_CONTEXT.matcher(prefix).find();
        return qualifiedPrefix || POSTFIX_CUSTOMER_OPERATED_QUALIFIER.matcher(suffix).find();
    }

    private boolean listConnector(String value) {
        String normalized = value.strip();
        return !LIST_BREAKING_PREDICATE.matcher(normalized).find()
            && normalized.matches("(?is)^[\\p{L}\\p{N}_\\s,/:()\\[\\]\\\"'`-]*$");
    }

    private List<SourceStatement> documentStatements(Path path, List<String> lines) {
        String name = path.getFileName().toString();
        if (name.endsWith(".json") || name.endsWith(".tsx") || name.endsWith(".html")
            || name.endsWith(".java")) {
            List<SourceStatement> statements = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                boolean foundStructuredValue = false;
                List<Pattern> valuePatterns = name.endsWith(".json")
                    ? List.of(JSON_STRING_VALUE)
                    : name.endsWith(".java")
                        ? List.of(JAVA_STRING_LITERAL)
                    : name.endsWith(".html")
                        ? List.of(MARKUP_TEXT_NODE, MARKUP_ATTRIBUTE_VALUE)
                        : List.of(JSON_STRING_VALUE, MARKUP_TEXT_NODE, MARKUP_ATTRIBUTE_VALUE);
                for (Pattern valuePattern : valuePatterns) {
                    Matcher structuredValue = valuePattern.matcher(line);
                    while (structuredValue.find()) {
                        String value = structuredDocumentValue(name, structuredValue.group(1)).strip();
                        if (!value.isBlank()) {
                            addDocumentText(statements, i + 1, value);
                            foundStructuredValue = true;
                        }
                    }
                }
                if (!foundStructuredValue && !line.isBlank()) {
                    addDocumentText(statements, i + 1, line);
                }
            }
            return statements;
        }
        return documentStatements(lines);
    }

    private String structuredDocumentValue(String fileName, String value) {
        if (fileName.endsWith(".html") || fileName.endsWith(".tsx")) {
            return HtmlUtils.htmlUnescape(value);
        }
        return value;
    }

    private List<SourceStatement> documentStatements(List<String> lines) {
        List<SourceStatement> statements = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        int blockStart = 1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isBlank()) {
                addDocumentBlock(statements, blockStart, block);
                block.setLength(0);
                continue;
            }
            if (startsDocumentStatement(line) && !block.isEmpty()) {
                addDocumentBlock(statements, blockStart, block);
                block.setLength(0);
            }
            if (block.isEmpty()) {
                blockStart = i + 1;
            } else {
                block.append('\n');
            }
            block.append(line);
            if (line.matches(".*[.!?][\\\"')\\]]*$")) {
                addDocumentBlock(statements, blockStart, block);
                block.setLength(0);
            }
        }
        addDocumentBlock(statements, blockStart, block);
        return statements;
    }

    private boolean startsDocumentStatement(String line) {
        return line.matches("(?:[-*+] |\\d+[.)] |#{1,6}\\s|\\||```|>).*");
    }

    private void addDocumentBlock(List<SourceStatement> statements, int startLine, StringBuilder block) {
        if (block.isEmpty()) {
            return;
        }
        String text = block.toString();
        if (text.startsWith("|")) {
            List<String> cells = Stream.of(text.split("\\|", -1))
                .map(String::strip)
                .filter(cell -> !cell.isBlank())
                .toList();
            for (int i = 0; i < cells.size(); i++) {
                String cell = cells.get(i);
                if (i == 0 && cell.endsWith("?") && cells.size() > 1
                    && cells.get(1).matches("(?i)^(?:no\\b|not\\b).*$")) {
                    statements.add(new SourceStatement(startLine, cell + " " + cells.get(1)));
                    i++;
                } else {
                    addDocumentText(statements, startLine, cell);
                }
            }
            return;
        }
        addDocumentText(statements, startLine, text);
    }

    private void addDocumentText(List<SourceStatement> statements, int startLine, String text) {
        Matcher sentence = Pattern.compile(".*?[.!?](?=\\s|$)|.+$", Pattern.DOTALL).matcher(text);
        while (sentence.find()) {
            String value = sentence.group().strip();
            if (!value.isBlank()) {
                int line = startLine + newlineCount(text, sentence.start());
                statements.add(new SourceStatement(line, value));
            }
        }
    }

    private List<Path> customerFacingTextFiles() throws IOException {
        return customerFacingTextFiles(repoRoot());
    }

    private List<Path> customerFacingTextFiles(Path root) throws IOException {
        Set<Path> paths = new LinkedHashSet<>();
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
            "frontend/public",
            "backend/src/main/resources/templates/emails",
            "backend/src/main/java/ooo/klae/connex/backend/services",
            "backend/src/main/java/ooo/klae/connex/backend/notifications")) {
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
            || name.endsWith(".html") || name.endsWith("EmailService.java")
            || name.equals("EmailNotificationDispatcher.java") || name.equals("WorkspaceMailConfigService.java");
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
        Path main = repoRoot().resolve("backend/src/main/java");
        return singleChangeFieldViolations(main, javaSourceFiles(main));
    }

    private List<String> singleChangeFieldViolations(Path root, List<Path> files) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String source = withoutJavaCommentsPreservingLines(
                Files.readString(file, StandardCharsets.UTF_8));
            for (SourceStatement statement : javaStatements(source)) {
                Matcher matcher = SINGLE_CHANGE_FIELD.matcher(statement.text());
                while (matcher.find()) {
                    if (secretAuditField(matcher.group(1))) {
                        int line = statement.line() + newlineCount(statement.text(), matcher.start());
                        violations.add(displayPath(root, file) + ":" + line + ": " + matcher.group(1));
                    }
                }
            }
        }
        return violations;
    }

    private List<String> secretAccessorSinkViolations() throws IOException {
        Path main = repoRoot().resolve("backend/src/main/java");
        Set<String> seenApproved = new LinkedHashSet<>();
        List<String> violations = secretAccessorSinkViolations(main, javaSourceFiles(main), seenApproved);
        List<String> stale = APPROVED_SECRET_SINKS.keySet().stream()
            .filter(site -> !seenApproved.contains(site))
            .sorted()
            .toList();
        assertTrue(stale.isEmpty(),
            "Approved local-development secret log sinks were not found at their exact reviewed sites: " + stale);
        return violations;
    }

    private List<String> secretAccessorSinkViolations(Path root, List<Path> files) throws IOException {
        return secretAccessorSinkViolations(root, files, new LinkedHashSet<>());
    }

    private List<String> secretAccessorSinkViolations(Path root, List<Path> files,
            Set<String> seenApproved) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String source = withoutJavaCommentsPreservingLines(
                Files.readString(file, StandardCharsets.UTF_8));
            String sourcePath = displayPath(root, file);
            Set<String> secretStoreVariables = secretStoreVariables(source);
            boolean secretStoreClass = SECRET_STORE_CLASS.matcher(source).find();
            Deque<Map<String, VariableState>> variableScopes = new ArrayDeque<>();
            variableScopes.push(new LinkedHashMap<>());
            for (SourceStatement statement : javaStatements(source)) {
                Matcher assignment = LOCAL_ASSIGNMENT.matcher(statement.text());
                if (assignment.find()) {
                    boolean declaration = !assignment.group(1).isBlank();
                    String variable = assignment.group(2);
                    String expression = assignment.group(3);
                    Map<String, VariableState> visibleVariables = visibleVariableStates(variableScopes);
                    boolean secret = containsSecretValue(variable, expression, taintedVariableNames(visibleVariables),
                        secretStoreVariables, secretStoreClass);
                    assignVariable(variableScopes, variable, expression, secret, declaration);
                } else {
                    Matcher declaration = LOCAL_DECLARATION.matcher(statement.text());
                    if (declaration.find()) {
                        variableScopes.peek().put(declaration.group(1), new VariableState(false, null));
                    }
                }
                Map<String, VariableState> visibleVariables = visibleVariableStates(variableScopes);
                Set<String> taintedVariables = taintedVariableNames(visibleVariables);
                Matcher sink = LOG_AUDIT_EXCEPTION_STATEMENT.matcher(statement.text());
                boolean directSecret = RISKY_SECRET_ACCESSOR.matcher(statement.text()).find()
                    || PLAINTEXT_SECRET_CALL.matcher(statement.text()).find()
                    || secretStoreGetCall(statement.text(), secretStoreVariables, secretStoreClass);
                Set<String> referencedSecrets = referencedSecretVariables(statement.text(), taintedVariables);
                if (sink.find() && (directSecret || !referencedSecrets.isEmpty())
                    && !approvedSecretSink(sourcePath, statement.text(), directSecret, referencedSecrets,
                        visibleVariables, seenApproved)) {
                    int line = statement.line() + newlineCount(statement.text(), sink.start());
                    violations.add(sourcePath + ":" + line + ": " + statement.text().strip());
                }
                String stripped = statement.text().strip();
                if (stripped.endsWith("}") && variableScopes.size() > 1) {
                    variableScopes.pop();
                }
                if (stripped.endsWith("{")) {
                    variableScopes.push(new LinkedHashMap<>());
                }
            }
        }
        return violations;
    }

    private void assignVariable(Deque<Map<String, VariableState>> scopes, String variable, String expression,
            boolean secret, boolean declaration) {
        Map<String, VariableState> owner = declaration ? scopes.peek() : variableOwner(scopes, variable);
        if (owner == null) {
            owner = scopes.peek();
        }
        VariableState previous = owner.get(variable);
        boolean tainted = secret || previous != null && previous.tainted();
        String origin = secret ? expression.strip() : null;
        owner.put(variable, new VariableState(tainted, origin));
    }

    private Map<String, VariableState> variableOwner(Deque<Map<String, VariableState>> scopes, String variable) {
        for (Map<String, VariableState> scope : scopes) {
            if (scope.containsKey(variable)) {
                return scope;
            }
        }
        return null;
    }

    private Map<String, VariableState> visibleVariableStates(Deque<Map<String, VariableState>> scopes) {
        Map<String, VariableState> visible = new LinkedHashMap<>();
        for (Map<String, VariableState> scope : scopes) {
            for (Map.Entry<String, VariableState> entry : scope.entrySet()) {
                visible.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return visible;
    }

    private Set<String> taintedVariableNames(Map<String, VariableState> variables) {
        Set<String> tainted = new LinkedHashSet<>();
        for (Map.Entry<String, VariableState> entry : variables.entrySet()) {
            if (entry.getValue().tainted()) {
                tainted.add(entry.getKey());
            }
        }
        return tainted;
    }

    private boolean containsSecretValue(String variable, String expression, Set<String> taintedVariables,
            Set<String> secretStoreVariables, boolean secretStoreClass) {
        boolean directSecret = SECRET_VALUE_ACCESSOR.matcher(expression).find()
            || PLAINTEXT_SECRET_CALL.matcher(expression).find()
            || secretStoreGetCall(expression, secretStoreVariables, secretStoreClass);
        Set<String> references = referencedSecretVariables(expression, taintedVariables);
        if (!directSecret && references.isEmpty()) {
            return SECRET_VALUE_IDENTIFIER.matcher(variable).matches();
        }
        String code = withoutJavaLiteralsPreservingLines(expression).strip();
        if (valuePreservingExpression(code, references)) {
            return true;
        }
        return directSecret && rootSecretCall(code, secretStoreVariables, secretStoreClass);
    }

    private boolean valuePreservingExpression(String code, Set<String> references) {
        if (code.contains("+") || code.contains("?") || code.contains(".queryParam(")
            || code.contains("String.valueOf(") || code.contains("String.format(")
            || code.contains(".formatted(") || code.matches("(?s)^new\\s+String\\s*\\(.*")) {
            return true;
        }
        for (String reference : references) {
            if (code.matches("(?s)(?:\\([^)]*\\)\\s*)*" + Pattern.quote(reference)
                + "(?:\\s*\\.[A-Za-z_$][A-Za-z0-9_$]*\\s*\\([^;]*\\))*")) {
                return true;
            }
        }
        return false;
    }

    private boolean rootSecretCall(String code, Set<String> secretStoreVariables, boolean secretStoreClass) {
        String uncast = code.replaceFirst("^(?:\\([^)]*\\)\\s*)+", "");
        Matcher accessor = SECRET_VALUE_ACCESSOR.matcher(uncast);
        if (accessor.find() && uncast.substring(0, accessor.start())
                .matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            return true;
        }
        Matcher plaintext = PLAINTEXT_SECRET_CALL.matcher(uncast);
        if (plaintext.find() && uncast.substring(0, plaintext.start())
                .matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            return true;
        }
        return secretStoreGetCall(uncast, secretStoreVariables, secretStoreClass)
            && uncast.matches("(?s)(?:this\\s*\\.\\s*)?[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*get\\s*\\(.*");
    }

    private Set<String> referencedSecretVariables(String source, Set<String> taintedVariables) {
        Set<String> references = new LinkedHashSet<>();
        Matcher identifier = JAVA_IDENTIFIER.matcher(withoutJavaLiteralsPreservingLines(source));
        while (identifier.find()) {
            String name = identifier.group(1);
            if (taintedVariables.contains(name) || SECRET_VALUE_IDENTIFIER.matcher(name).matches()) {
                references.add(name);
            }
        }
        return references;
    }

    private boolean approvedSecretSink(String sourcePath, String statement, boolean directSecret,
            Set<String> referencedSecrets, Map<String, VariableState> visibleVariables,
            Set<String> seenApproved) {
        if (directSecret || referencedSecrets.isEmpty()) {
            return false;
        }
        Set<String> matches = new LinkedHashSet<>();
        for (String variable : referencedSecrets) {
            String key = sourcePath + "#" + variable;
            ApprovedSecretSink approved = APPROVED_SECRET_SINKS.get(key);
            VariableState state = visibleVariables.get(variable);
            if (approved == null || state == null || state.origin() == null
                || !normalizeJavaStatement(statement).equals(approved.statement())
                || !approved.origin().matcher(state.origin()).find()) {
                return false;
            }
            matches.add(key);
        }
        seenApproved.addAll(matches);
        return true;
    }

    private String normalizeJavaStatement(String statement) {
        return statement.strip().replaceAll("\\s+", " ");
    }

    private Set<String> secretStoreVariables(String source) {
        Set<String> variables = new LinkedHashSet<>();
        Matcher declaration = SECRET_STORE_VARIABLE.matcher(source);
        while (declaration.find()) {
            variables.add(declaration.group(1));
        }
        return variables;
    }

    private boolean secretStoreGetCall(String statement, Set<String> variables, boolean secretStoreClass) {
        for (String variable : variables) {
            Pattern call = Pattern.compile("\\b(?:this\\s*\\.\\s*)?" + Pattern.quote(variable)
                + "\\s*\\.\\s*get\\s*\\(");
            if (call.matcher(statement).find()) {
                return true;
            }
        }
        return secretStoreClass && Pattern.compile("(?<!\\.)\\bget\\s*\\(").matcher(statement).find();
    }

    private List<String> responsePayloadSecretFieldViolations() throws IOException {
        Path main = repoRoot().resolve("backend/src/main/java");
        Path dto = repoRoot().resolve("backend/src/main/java/ooo/klae/connex/backend/dto");
        Set<Path> responseFiles = controllerResponsePayloadFiles(main);
        Set<Path> payloadFiles = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(dto)) {
            payloadFiles.addAll(files
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .toList());
        }
        payloadFiles.addAll(responseFiles);
        List<Path> files = payloadFiles.stream()
            .sorted(Comparator.comparing(Path::toString))
            .toList();
        Set<String> seenApproved = new LinkedHashSet<>();
        List<String> violations = responsePayloadSecretFieldViolations(main, files, responseFiles, seenApproved);
        Set<String> approvedFields = new LinkedHashSet<>(APPROVED_SECRET_INPUT_FIELDS);
        approvedFields.addAll(APPROVED_SECRET_RESPONSE_FIELDS);
        List<String> stale = approvedFields.stream()
            .filter(field -> !seenApproved.contains(field))
            .sorted()
            .toList();
        assertTrue(stale.isEmpty(),
            "Approved credential-input or response-metadata fields were not found; remove stale exact "
                + "allowlist entries: " + stale);
        return violations;
    }

    private List<String> responsePayloadSecretFieldViolations(Path root, List<Path> files) throws IOException {
        return responsePayloadSecretFieldViolations(root, files, Set.of());
    }

    private List<String> responsePayloadSecretFieldViolations(Path root, List<Path> files,
            Set<Path> responseFiles) throws IOException {
        return responsePayloadSecretFieldViolations(root, files, responseFiles, new LinkedHashSet<>());
    }

    private List<String> responsePayloadSecretFieldViolations(Path root, List<Path> files, Set<Path> responseFiles,
            Set<String> seenApproved) throws IOException {
        List<String> violations = new ArrayList<>();
        Set<Path> normalizedResponseFiles = responseFiles.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (Path file : files) {
            String source = withoutJavaCommentsPreservingLines(
                Files.readString(file, StandardCharsets.UTF_8));
            String sourcePath = displayPath(root, file);
            boolean responsePayload = normalizedResponseFiles.contains(file.toAbsolutePath().normalize());
            for (PayloadField field : payloadFields(source)) {
                String approvedKey = sourcePath + "#" + field.name();
                boolean approvedInput = APPROVED_SECRET_INPUT_FIELDS.contains(approvedKey);
                boolean approvedResponse = APPROVED_SECRET_RESPONSE_FIELDS.contains(approvedKey);
                if ((approvedInput || (approvedResponse && responsePayload)) && !field.jsonIgnored()) {
                    seenApproved.add(approvedKey);
                }
                boolean approved = (approvedResponse && responsePayload) || (approvedInput && !responsePayload);
                if (RESPONSE_PAYLOAD_SECRET_FIELD.matcher(field.name()).find()
                    && !field.jsonIgnored()
                    && !approved) {
                    violations.add(sourcePath + ":" + field.line() + ": " + field.name());
                }
            }
        }
        return violations;
    }

    private Set<Path> controllerResponsePayloadFiles(Path main) {
        Set<Class<?>> payloadTypes = new LinkedHashSet<>();
        Set<Class<?>> visitedTypes = new LinkedHashSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Controller")));
        Set<BeanDefinition> controllers =
            scanner.findCandidateComponents("ooo.klae.connex.backend.controllers");
        assertTrue(controllers.size() >= 20,
            "Expected to scan backend controllers but found " + controllers.size()
                + "; the response-payload guard would pass vacuously.");

        for (BeanDefinition definition : controllers) {
            try {
                Class<?> controller = Class.forName(definition.getBeanClassName(), false,
                    EncryptionGuardrailArchTest.class.getClassLoader());
                for (Method method : controller.getMethods()) {
                    if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) {
                        collectProjectPayloadTypes(method.getGenericReturnType(), payloadTypes, visitedTypes);
                    }
                }
            } catch (ClassNotFoundException e) {
                fail("Could not inspect controller response types for " + definition.getBeanClassName() + ": "
                    + e.getMessage());
            }
        }

        Set<Path> files = new LinkedHashSet<>();
        for (Class<?> payloadType : payloadTypes) {
            String topLevelName = payloadType.getName().split("\\$", 2)[0];
            Path source = main.resolve(topLevelName.replace('.', '/') + ".java");
            if (Files.exists(source)) {
                files.add(source);
            }
        }
        return files;
    }

    private void collectProjectPayloadTypes(Type type, Set<Class<?>> payloadTypes, Set<Class<?>> visitedTypes) {
        if (type instanceof ParameterizedType parameterized) {
            collectProjectPayloadTypes(parameterized.getRawType(), payloadTypes, visitedTypes);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collectProjectPayloadTypes(argument, payloadTypes, visitedTypes);
            }
            return;
        }
        if (type instanceof GenericArrayType array) {
            collectProjectPayloadTypes(array.getGenericComponentType(), payloadTypes, visitedTypes);
            return;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                collectProjectPayloadTypes(bound, payloadTypes, visitedTypes);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                collectProjectPayloadTypes(bound, payloadTypes, visitedTypes);
            }
            return;
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                collectProjectPayloadTypes(bound, payloadTypes, visitedTypes);
            }
            return;
        }
        if (!(type instanceof Class<?> payloadType)) {
            return;
        }
        if (payloadType.isArray()) {
            collectProjectPayloadTypes(payloadType.getComponentType(), payloadTypes, visitedTypes);
            return;
        }
        if (!payloadType.getPackageName().startsWith("ooo.klae.connex.backend")
            || !visitedTypes.add(payloadType)) {
            return;
        }
        payloadTypes.add(payloadType);
        for (Field field : payloadType.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                collectProjectPayloadTypes(field.getGenericType(), payloadTypes, visitedTypes);
            }
        }
        Type superclass = payloadType.getGenericSuperclass();
        if (superclass != null) {
            collectProjectPayloadTypes(superclass, payloadTypes, visitedTypes);
        }
    }

    private List<PayloadField> payloadFields(String source) {
        List<PayloadField> fields = new ArrayList<>();
        Matcher field = RESPONSE_PAYLOAD_FIELD.matcher(source);
        while (field.find()) {
            if (field.group().matches("(?s).*\\bstatic\\b.*")) {
                continue;
            }
            String prefix = source.substring(fieldBoundary(source, field.start()), field.start());
            boolean ignored = excludedFromJsonOutput(prefix);
            int line = lineNumber(source, field.start());
            addPayloadField(fields, new PayloadField(line, field.group(1), ignored));
            addSerializedPayloadFields(fields, prefix, line, ignored);
        }
        Matcher declaration = RESPONSE_PAYLOAD_DECLARATION.matcher(source);
        while (declaration.find()) {
            if (declaration.group().matches("(?s).*\\bstatic\\b.*")) {
                continue;
            }
            String prefix = source.substring(fieldBoundary(source, declaration.start()), declaration.start());
            boolean ignored = excludedFromJsonOutput(prefix);
            for (SourceSlice name : topLevelDeclaredNames(declaration.group())) {
                int offset = declaration.start() + name.offset();
                addPayloadField(fields, new PayloadField(lineNumber(source, offset), name.text(),
                    ignored));
            }
            addSerializedPayloadFields(fields, prefix, lineNumber(source, declaration.start()), ignored);
        }
        Matcher annotatedField = JSON_ANNOTATED_FIELD.matcher(source);
        while (annotatedField.find()) {
            boolean ignored = excludedFromJsonOutput(annotatedField.group());
            int line = lineNumber(source, annotatedField.start());
            addPayloadField(fields, new PayloadField(line, annotatedField.group(1), ignored));
            addSerializedPayloadFields(fields, annotatedField.group(), line, ignored);
        }

        Matcher record = JAVA_RECORD_DECLARATION.matcher(source);
        while (record.find()) {
            int open = record.end() - 1;
            int close = matchingDelimiter(source, open, '(', ')');
            if (close < 0) {
                continue;
            }
            String components = source.substring(open + 1, close);
            for (SourceSlice component : topLevelComponents(components)) {
                Matcher identifier = JAVA_IDENTIFIER_AT_END.matcher(component.text().strip());
                if (identifier.find()) {
                    int offset = open + 1 + component.offset();
                    boolean ignored = excludedFromJsonOutput(component.text());
                    addPayloadField(fields, new PayloadField(lineNumber(source, offset), identifier.group(1),
                        ignored));
                    addSerializedPayloadFields(fields, component.text(), lineNumber(source, offset), ignored);
                }
            }
        }
        Matcher getter = RESPONSE_PAYLOAD_GETTER.matcher(source);
        while (getter.find()) {
            String prefix = source.substring(fieldBoundary(source, getter.start()), getter.start());
            String suffix = getter.group(1);
            String name = Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
            boolean ignored = excludedFromJsonOutput(prefix);
            int line = lineNumber(source, getter.start());
            addPayloadField(fields, new PayloadField(line, name, ignored));
            addSerializedPayloadFields(fields, prefix, line, ignored);
        }
        Matcher annotatedGetter = JSON_ANNOTATED_GETTER.matcher(source);
        while (annotatedGetter.find()) {
            String prefix = source.substring(fieldBoundary(source, annotatedGetter.start()), annotatedGetter.start());
            boolean ignored = excludedFromJsonOutput(prefix + annotatedGetter.group());
            int line = lineNumber(source, annotatedGetter.start());
            addPayloadField(fields, new PayloadField(line, getterPayloadName(annotatedGetter.group(1)), ignored));
            addSerializedPayloadFields(fields, annotatedGetter.group(), line, ignored);
        }
        return fields;
    }

    private String getterPayloadName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3
            && Character.isUpperCase(methodName.charAt(3))) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2
            && Character.isUpperCase(methodName.charAt(2))) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        return methodName;
    }

    private void addPayloadField(List<PayloadField> fields, PayloadField candidate) {
        if (fields.stream().noneMatch(field -> field.line() == candidate.line()
            && field.name().equals(candidate.name()))) {
            fields.add(candidate);
        }
    }

    private void addSerializedPayloadFields(List<PayloadField> fields, String source, int line,
            boolean ignored) {
        Matcher serializedName = JSON_SERIALIZED_NAME.matcher(source);
        while (serializedName.find()) {
            String name = serializedName.group(1) == null ? serializedName.group(2) : serializedName.group(1);
            addPayloadField(fields, new PayloadField(line, name, ignored));
        }
    }

    private List<SourceSlice> topLevelDeclaredNames(String declaration) {
        List<SourceSlice> names = new ArrayList<>();
        int angles = 0;
        int parentheses = 0;
        for (int i = 0; i < declaration.length(); i++) {
            char value = declaration.charAt(i);
            if (value == '<') {
                angles++;
            } else if (value == '>') {
                angles = Math.max(0, angles - 1);
            } else if (value == '(') {
                parentheses++;
            } else if (value == ')') {
                parentheses--;
            } else if (angles == 0 && parentheses == 0 && Character.isJavaIdentifierStart(value)) {
                int end = i + 1;
                while (end < declaration.length()
                    && Character.isJavaIdentifierPart(declaration.charAt(end))) {
                    end++;
                }
                int next = end;
                while (next < declaration.length() && Character.isWhitespace(declaration.charAt(next))) {
                    next++;
                }
                if (next < declaration.length()
                    && (declaration.charAt(next) == ',' || declaration.charAt(next) == '='
                        || declaration.charAt(next) == ';')) {
                    names.add(new SourceSlice(i, declaration.substring(i, end)));
                }
                i = end - 1;
            }
        }
        return names;
    }

    private boolean excludedFromJsonOutput(String source) {
        return EFFECTIVE_JSON_IGNORE.matcher(source).find() || JSON_WRITE_ONLY.matcher(source).find();
    }

    private int fieldBoundary(String source, int fieldStart) {
        int semicolon = source.lastIndexOf(';', fieldStart - 1);
        int openBrace = source.lastIndexOf('{', fieldStart - 1);
        int closeBrace = source.lastIndexOf('}', fieldStart - 1);
        return Math.max(semicolon, Math.max(openBrace, closeBrace)) + 1;
    }

    private List<SourceSlice> topLevelComponents(String components) {
        List<SourceSlice> slices = new ArrayList<>();
        int start = 0;
        int parentheses = 0;
        int angles = 0;
        int brackets = 0;
        for (int i = 0; i < components.length(); i++) {
            char value = components.charAt(i);
            if (value == '(') {
                parentheses++;
            } else if (value == ')') {
                parentheses--;
            } else if (value == '<') {
                angles++;
            } else if (value == '>') {
                angles = Math.max(0, angles - 1);
            } else if (value == '[') {
                brackets++;
            } else if (value == ']') {
                brackets--;
            } else if (value == ',' && parentheses == 0 && angles == 0 && brackets == 0) {
                slices.add(new SourceSlice(start, components.substring(start, i)));
                start = i + 1;
            }
        }
        slices.add(new SourceSlice(start, components.substring(start)));
        return slices;
    }

    private int matchingDelimiter(String source, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value == opening) {
                depth++;
            } else if (value == closing && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private List<SourceStatement> javaStatements(String source) {
        List<SourceStatement> statements = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        int statementLine = 1;
        int line = 1;
        boolean quoted = false;
        boolean character = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (statement.isEmpty() && !Character.isWhitespace(value)) {
                statementLine = line;
            }
            statement.append(value);
            if (value == '\n') {
                line++;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((quoted || character) && value == '\\') {
                escaped = true;
                continue;
            }
            if (!character && value == '"') {
                quoted = !quoted;
            } else if (!quoted && value == '\'') {
                character = !character;
            } else if (!quoted && !character && (value == ';' || value == '{' || value == '}')) {
                statements.add(new SourceStatement(statementLine, statement.toString()));
                statement.setLength(0);
            }
        }
        if (!statement.toString().isBlank()) {
            statements.add(new SourceStatement(statementLine, statement.toString()));
        }
        return statements;
    }

    private List<String> credentialStorageViolations(Path root, List<Path> files) throws IOException {
        Set<String> violations = new LinkedHashSet<>();
        Map<String, Integer> actualStorageReferences = new LinkedHashMap<>();
        Map<String, Integer> actualPurposeReferences = new LinkedHashMap<>();
        Set<String> scannedPaths = new LinkedHashSet<>();
        for (Path file : files) {
            String source = withoutJavaLiteralsPreservingLines(
                withoutJavaComments(Files.readString(file, StandardCharsets.UTF_8)));
            String sourcePath = displayPath(root, file);
            scannedPaths.add(sourcePath);
            Set<String> declaredTypes = declaredTypeNames(source);
            Matcher storageReference = CREDENTIAL_STORAGE_REFERENCE.matcher(source);
            while (storageReference.find()) {
                String name = storageReference.group(1);
                if (declaredTypes.contains(name) || importStatementAt(source, storageReference.start())) {
                    continue;
                }
                String reference = sourcePath + "#" + name;
                actualStorageReferences.merge(reference, 1, Integer::sum);
                if (!APPROVED_CREDENTIAL_STORAGE_REFERENCES.containsKey(reference)) {
                    violations.add(reference);
                }
            }
            Matcher purposeReference = SECRET_PURPOSE_REFERENCE.matcher(source);
            while (purposeReference.find()) {
                String reference = sourcePath + "#" + purposeReference.group(1);
                actualPurposeReferences.merge(reference, 1, Integer::sum);
                if (!APPROVED_SECRET_PURPOSE_REFERENCES.containsKey(reference)) {
                    violations.add(reference);
                }
            }
        }
        addReferenceCountViolations(APPROVED_CREDENTIAL_STORAGE_REFERENCES, actualStorageReferences,
            scannedPaths, violations);
        addReferenceCountViolations(APPROVED_SECRET_PURPOSE_REFERENCES, actualPurposeReferences,
            scannedPaths, violations);
        return List.copyOf(violations);
    }

    private Set<String> declaredTypeNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher type = JAVA_TYPE_OPEN.matcher(source);
        while (type.find()) {
            names.add(type.group(1));
        }
        return names;
    }

    private boolean importStatementAt(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int lineEnd = source.indexOf('\n', offset);
        String line = source.substring(lineStart, lineEnd < 0 ? source.length() : lineEnd).strip();
        return line.startsWith("import ");
    }

    private void addReferenceCountViolations(Map<String, Integer> expectedReferences,
            Map<String, Integer> actualReferences, Set<String> scannedPaths, Set<String> violations) {
        for (Map.Entry<String, Integer> expected : expectedReferences.entrySet()) {
            String sourcePath = expected.getKey().substring(0, expected.getKey().indexOf('#'));
            if (!scannedPaths.contains(sourcePath)) {
                continue;
            }
            int actual = actualReferences.getOrDefault(expected.getKey(), 0);
            if (actual != expected.getValue()) {
                violations.add(expected.getKey() + " expected " + expected.getValue()
                    + " reviewed reference(s), found " + actual);
            }
        }
    }

    private Set<String> secretPurposeDeclarations(Path purposeFile) throws IOException {
        String source = withoutJavaComments(Files.readString(purposeFile, StandardCharsets.UTF_8));
        int enumStart = source.indexOf("enum SecretPurpose");
        int bodyStart = enumStart < 0 ? -1 : source.indexOf('{', enumStart);
        int bodyEnd = bodyStart < 0 ? -1 : source.indexOf(';', bodyStart);
        assertTrue(bodyStart >= 0 && bodyEnd > bodyStart,
            "Could not parse the SecretPurpose enum constant catalog.");
        Set<String> purposes = new LinkedHashSet<>();
        String declarations = source.substring(bodyStart + 1, bodyEnd);
        for (SourceSlice declaration : topLevelComponents(declarations)) {
            Matcher name = SECRET_PURPOSE_DECLARATION.matcher(declaration.text());
            if (name.find()) {
                purposes.add(name.group(1));
            }
        }
        return purposes;
    }

    private List<String> mybatisXmlCryptoTransformViolations(Path root, List<Path> files) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String source = withoutPatternComments(Files.readString(file, StandardCharsets.UTF_8), XML_COMMENT);
            Matcher transform = MYBATIS_XML_CRYPTO_TRANSFORM.matcher(source);
            while (transform.find()) {
                violations.add(displayPath(root, file) + ":" + lineNumber(source, transform.start()));
            }
        }
        return violations;
    }

    private List<String> transparentEncryptionHandlerViolations(Path root, List<Path> files) throws IOException {
        List<String> violations = new ArrayList<>();
        Map<Path, JavaSourceTypes> sources = new LinkedHashMap<>();
        Map<String, JavaTypeHierarchy> hierarchyByType = new LinkedHashMap<>();
        for (Path file : files) {
            String source = withoutJavaComments(Files.readString(file, StandardCharsets.UTF_8));
            JavaSourceTypes sourceTypes = javaSourceTypes(root, file, source);
            sources.put(file, sourceTypes);
            for (JavaTypeHierarchy hierarchy : sourceTypes.hierarchies()) {
                hierarchyByType.put(hierarchy.qualifiedName(), hierarchy);
            }
        }
        Set<String> handlerTypes = new LinkedHashSet<>();
        boolean changed;
        do {
            changed = false;
            for (JavaSourceTypes sourceTypes : sources.values()) {
                for (JavaTypeHierarchy hierarchy : sourceTypes.hierarchies()) {
                    boolean handlerParent = hierarchy.parents().stream()
                        .map(parent -> resolveJavaType(parent, sourceTypes, hierarchyByType.keySet()))
                        .anyMatch(parent -> mybatisTypeHandler(parent) || handlerTypes.contains(parent));
                    if (handlerParent && handlerTypes.add(hierarchy.qualifiedName())) {
                        changed = true;
                    }
                }
            }
        } while (changed);

        for (Map.Entry<Path, JavaSourceTypes> entry : sources.entrySet()) {
            JavaSourceTypes sourceTypes = entry.getValue();
            String source = sourceTypes.source();
            boolean declaredHandler = sourceTypes.hierarchies().stream()
                .anyMatch(hierarchy -> handlerTypes.contains(hierarchy.qualifiedName()));
            boolean anonymousHandler = anonymousImplementedTypes(source).stream()
                .map(type -> resolveJavaType(type, sourceTypes, hierarchyByType.keySet()))
                .anyMatch(type -> mybatisTypeHandler(type) || handlerTypes.contains(type));
            boolean typeHandler = declaredHandler
                || anonymousHandler
                || MYBATIS_TYPE_HANDLER_ANNOTATION.matcher(source).find();
            boolean converter = JPA_CONVERTER_REFERENCE.matcher(source).find();
            if ((typeHandler || converter) && transparentEncryptionReference(source)) {
                violations.add(displayPath(root, entry.getKey()));
            }
        }
        return violations;
    }

    private JavaSourceTypes javaSourceTypes(Path root, Path file, String source) {
        Matcher packageDeclaration = JAVA_PACKAGE.matcher(source);
        String packageName = packageDeclaration.find()
            ? packageDeclaration.group(1)
            : inferredPackage(root, file);
        Map<String, String> imports = new LinkedHashMap<>();
        Matcher importedType = JAVA_IMPORT.matcher(source);
        while (importedType.find()) {
            String qualifiedName = importedType.group(1);
            imports.put(simpleTypeName(qualifiedName), qualifiedName);
        }
        return new JavaSourceTypes(source, packageName, imports, typeHierarchies(source, packageName));
    }

    private String inferredPackage(Path root, Path file) {
        Path relativeParent = root.toAbsolutePath().normalize()
            .relativize(file.toAbsolutePath().normalize())
            .getParent();
        return relativeParent == null ? "" : relativeParent.toString().replace('/', '.').replace('\\', '.');
    }

    private String resolveJavaType(String typeName, JavaSourceTypes sourceTypes,
            Set<String> declaredTypes) {
        if (declaredTypes.contains(typeName)) {
            return typeName;
        }
        String samePackage = sourceTypes.packageName().isBlank()
            ? typeName
            : sourceTypes.packageName() + "." + typeName;
        if (declaredTypes.contains(samePackage)) {
            return samePackage;
        }
        if (typeName.contains(".")) {
            int separator = typeName.indexOf('.');
            String importedOuter = sourceTypes.imports().get(typeName.substring(0, separator));
            return importedOuter == null
                ? typeName
                : importedOuter + typeName.substring(separator);
        }
        String imported = sourceTypes.imports().get(typeName);
        if (imported != null) {
            return imported;
        }
        if (typeName.equals("TypeHandler") || typeName.equals("BaseTypeHandler")) {
            return "org.apache.ibatis.type." + typeName;
        }
        return samePackage;
    }

    private boolean mybatisTypeHandler(String qualifiedName) {
        return qualifiedName.equals("org.apache.ibatis.type.TypeHandler")
            || qualifiedName.equals("org.apache.ibatis.type.BaseTypeHandler");
    }

    private Set<String> anonymousImplementedTypes(String source) {
        Set<String> types = new LinkedHashSet<>();
        Matcher implementation = ANONYMOUS_TYPE_IMPLEMENTATION.matcher(source);
        while (implementation.find()) {
            types.add(implementation.group(1));
        }
        return types;
    }

    private String simpleTypeName(String qualifiedName) {
        int separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    private List<JavaTypeHierarchy> typeHierarchies(String source, String packageName) {
        List<JavaTypeHierarchy> hierarchies = new ArrayList<>();
        String structuralSource = withoutJavaLiteralsPreservingLines(source);
        Matcher declaration = JAVA_TYPE_HIERARCHY.matcher(structuralSource);
        while (declaration.find()) {
            Set<String> parents = new LinkedHashSet<>();
            String parentClause = declaration.group(2).replaceAll("\\bimplements\\b", ",");
            for (SourceSlice parent : topLevelComponents(parentClause)) {
                Matcher name = JAVA_PARENT_TYPE.matcher(parent.text().strip());
                if (name.find()) {
                    parents.add(name.group(1));
                }
            }
            String qualifiedName = qualifiedTypeName(structuralSource, declaration.start(), declaration.group(1),
                packageName);
            hierarchies.add(new JavaTypeHierarchy(qualifiedName, parents));
        }
        return hierarchies;
    }

    private String qualifiedTypeName(String source, int declarationOffset, String simpleName, String packageName) {
        List<String> names = new ArrayList<>();
        Matcher type = JAVA_TYPE_OPEN.matcher(source);
        while (type.find() && type.start() < declarationOffset) {
            int open = type.end() - 1;
            int close = matchingDelimiter(source, open, '{', '}');
            if (close < 0 || close > declarationOffset) {
                names.add(type.group(1));
            }
        }
        names.add(simpleName);
        String typeName = String.join(".", names);
        return packageName.isBlank() ? typeName : packageName + "." + typeName;
    }

    private boolean transparentEncryptionReference(String source) {
        return TRANSPARENT_ENCRYPTION_REFERENCE.matcher(source).find()
            || UNQUALIFIED_ENCRYPTION_CALL.matcher(source).find();
    }

    private List<String> cipherReferenceViolations(Path root, List<Path> files, Set<String> approvedSites,
            Set<String> seenApproved) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String source = withoutJavaComments(Files.readString(file, StandardCharsets.UTF_8));
            if (CIPHER_API_REFERENCE.matcher(source).find()) {
                String sourcePath = displayPath(root, file);
                if (approvedSites.contains(sourcePath)) {
                    seenApproved.add(sourcePath);
                } else {
                    violations.add(sourcePath);
                }
            }
        }
        return violations;
    }

    private String withoutJavaComments(String source) {
        return withoutJavaCommentsPreservingLines(source);
    }

    private String withoutPatternComments(String source, Pattern commentPattern) {
        Matcher comment = commentPattern.matcher(source);
        StringBuilder result = new StringBuilder(source.length());
        while (comment.find()) {
            String replacement = "\n".repeat(newlineCount(comment.group(), comment.group().length()));
            comment.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        comment.appendTail(result);
        return result.toString();
    }

    private String withoutJavaCommentsPreservingLines(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean quoted = false;
        boolean character = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) {
                if (value == '\n') {
                    result.append(value);
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (value == '\n') {
                    result.append(value);
                } else if (value == '*' && next == '/') {
                    result.append(' ');
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!quoted && !character && value == '/' && next == '/') {
                result.append(' ');
                lineComment = true;
                i++;
                continue;
            }
            if (!quoted && !character && value == '/' && next == '*') {
                result.append(' ');
                blockComment = true;
                i++;
                continue;
            }
            result.append(value);
            if (escaped) {
                escaped = false;
            } else if ((quoted || character) && value == '\\') {
                escaped = true;
            } else if (!character && value == '"') {
                quoted = !quoted;
            } else if (!quoted && value == '\'') {
                character = !character;
            }
        }
        return result.toString();
    }

    private String withoutJavaLiteralsPreservingLines(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean quoted = false;
        boolean character = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (escaped) {
                result.append(value == '\n' ? '\n' : ' ');
                escaped = false;
                continue;
            }
            if ((quoted || character) && value == '\\') {
                result.append(' ');
                escaped = true;
                continue;
            }
            if (!character && value == '"') {
                quoted = !quoted;
                result.append(' ');
                continue;
            }
            if (!quoted && value == '\'') {
                character = !character;
                result.append(' ');
                continue;
            }
            result.append(quoted || character ? (value == '\n' ? '\n' : ' ') : value);
        }
        return result.toString();
    }

    private Path writeFixture(Path root, String relativePath, String source) throws IOException {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
        return path;
    }

    private int lineNumber(String source, int offset) {
        return 1 + newlineCount(source, offset);
    }

    private int newlineCount(String source, int endExclusive) {
        int count = 0;
        for (int i = 0; i < endExclusive; i++) {
            if (source.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private String displayPath(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
            .toString()
            .replace('\\', '/');
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

    private record PayloadField(int line, String name, boolean jsonIgnored) {}

    private record VariableState(boolean tainted, String origin) {}

    private record ApprovedSecretSink(String statement, Pattern origin) {}

    private record SourceSlice(int offset, String text) {}

    private record SourceStatement(int line, String text) {}

    private record JavaSourceTypes(
        String source,
        String packageName,
        Map<String, String> imports,
        List<JavaTypeHierarchy> hierarchies
    ) {}

    private record JavaTypeHierarchy(String qualifiedName, Set<String> parents) {}
}
