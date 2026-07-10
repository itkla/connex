package ooo.klae.connex.backend.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

/**
 * Fails deployed startup before datasource creation when the database transport is not verified TLS, while preserving
 * localhost-only settings for the systemd-controlled local staging checkout.
 */
public class DatabaseTransportSecurityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Set<String> VERIFIED_SSL_MODES = Set.of("verify_ca", "verify_identity");
    private static final Set<String> LOCAL_PLAINTEXT_SSL_MODES = Set.of("disabled");
    private static final String MYSQL_SIMPLE_URL_PREFIX = "jdbc:mysql://";
    private static final String LOCAL_SYSTEMD_STAGING_WORKING_DIRECTORY = "/opt/connex-staging/backend";
    private static final String SYSTEMD_INVOCATION_ID_PROPERTY = "INVOCATION_ID";
    private static final String LOCAL_SYSTEMD_STAGING_PROPERTY_SOURCE = "connexLocalSystemdStaging";
    private static final Map<String, Object> LOCAL_SYSTEMD_STAGING_DEFAULTS = Map.of(
        "CONNEX_SESSION_COOKIE_SECURE",
        "false",
        "CONNEX_WORKSPACE_COOKIE_SECURE",
        "false",
        "CONNEX_CORS_ALLOWED_ORIGINS",
        "http://localhost:3001",
        "CONNEX_WEBAUTHN_ALLOWED_ORIGINS",
        "http://localhost:3001",
        "CONNEX_WEBAUTHN_RP_ID",
        "localhost"
    );
    private static final Set<String> HIKARI_JDBC_URL_PROPERTIES = Set.of("springdatasourcehikarijdbcurl");
    private static final Set<String> DATASOURCE_IMPLEMENTATION_OVERRIDE_PROPERTIES = Set.of(
        "springdatasourcetype",
        "springdatasourcejndiname",
        "springdatasourcehikaridatasourceclassname",
        "springdatasourcehikaridatasourcejndi"
    );
    private static final Set<String> HIKARI_TLS_MODE_PROPERTIES = Set.of(
        "sslmode",
        "usessl",
        "requiressl",
        "verifyservercertificate"
    );
    private static final Set<String> DIRECT_HIKARI_JDBC_URL_PROPERTIES = Set.of(
        "spring.datasource.hikari.jdbc-url",
        "spring.datasource.hikari.jdbcUrl"
    );
    private static final Set<String> DIRECT_HIKARI_TLS_MODE_PROPERTIES = Set.of(
        "spring.datasource.hikari.data-source-properties.sslMode",
        "spring.datasource.hikari.data-source-properties.ssl-mode",
        "spring.datasource.hikari.data-source-properties.useSSL",
        "spring.datasource.hikari.data-source-properties.use-ssl",
        "spring.datasource.hikari.data-source-properties.requireSSL",
        "spring.datasource.hikari.data-source-properties.require-ssl",
        "spring.datasource.hikari.data-source-properties.verifyServerCertificate",
        "spring.datasource.hikari.data-source-properties.verify-server-certificate",
        "spring.datasource.hikari.dataSourceProperties.sslMode",
        "spring.datasource.hikari.dataSourceProperties.useSSL",
        "spring.datasource.hikari.dataSourceProperties.requireSSL",
        "spring.datasource.hikari.dataSourceProperties.verifyServerCertificate"
    );
    private static final String HIKARI_DATA_SOURCE_PROPERTIES_PREFIX = "springdatasourcehikaridatasourceproperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        applyLocalSystemdStagingDefaults(environment);

        if (isLocalSystemdStaging(environment) && environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("Local systemd staging must not run with dev or test profiles active");
        }

        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            return;
        }

        String url = requiredProperty(environment, "spring.datasource.url", "CONNEX_DB_URL");
        requiredProperty(environment, "spring.datasource.username", "CONNEX_DB_USERNAME");
        requiredProperty(environment, "spring.datasource.password", "CONNEX_DB_PASSWORD");

        if (!allowsLocalSystemdStagingPlaintextMysqlUrl(environment, url)) {
            validateVerifiedMysqlUrl(url, "CONNEX_DB_URL");
        }
        validateOverridingDatasourceConfiguration(environment);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static String requiredProperty(ConfigurableEnvironment environment, String propertyName, String envName) {
        String value;
        try {
            value = environment.getProperty(propertyName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(envName + " must be set outside dev/test", ex);
        }
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(envName + " must be set outside dev/test");
        }
        return value.strip();
    }

    private static void validateOverridingDatasourceConfiguration(ConfigurableEnvironment environment) {
        Set<String> checkedHikariUrlProperties = new HashSet<>();
        Set<String> checkedTlsProperties = new HashSet<>();

        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (propertySource instanceof EnumerablePropertySource<?> enumerablePropertySource) {
                for (String propertyName : enumerablePropertySource.getPropertyNames()) {
                    String canonicalName = canonicalize(propertyName);
                    if (DATASOURCE_IMPLEMENTATION_OVERRIDE_PROPERTIES.contains(canonicalName)) {
                        throw new IllegalStateException(
                            propertyName + " cannot override the validated datasource outside dev/test"
                        );
                    }
                    if (HIKARI_JDBC_URL_PROPERTIES.contains(canonicalName)) {
                        validateOptionalHikariUrl(environment, propertyName, propertySource.getProperty(propertyName));
                        checkedHikariUrlProperties.add(canonicalName);
                    }
                    if (isHikariTlsModeProperty(canonicalName)) {
                        checkedTlsProperties.add(canonicalName);
                        throw new IllegalStateException(
                            propertyName + " cannot override database TLS mode outside dev/test"
                        );
                    }
                }
            }
        }

        for (String propertyName : Set.of(
            "spring.datasource.type",
            "spring.datasource.jndi-name",
            "spring.datasource.hikari.data-source-class-name",
            "spring.datasource.hikari.data-source-jndi",
            "spring.datasource.hikari.data-source-j-n-d-i",
            "spring.datasource.hikari.dataSourceJNDI"
        )) {
            if (optionalProperty(environment, propertyName) != null) {
                throw new IllegalStateException(
                    propertyName + " cannot override the validated datasource outside dev/test"
                );
            }
        }

        for (String propertyName : DIRECT_HIKARI_JDBC_URL_PROPERTIES) {
            String canonicalName = canonicalize(propertyName);
            if (!checkedHikariUrlProperties.contains(canonicalName)) {
                validateOptionalHikariUrl(environment, propertyName, optionalProperty(environment, propertyName));
            }
        }

        for (String propertyName : DIRECT_HIKARI_TLS_MODE_PROPERTIES) {
            String canonicalName = canonicalize(propertyName);
            if (!checkedTlsProperties.contains(canonicalName) && optionalProperty(environment, propertyName) != null) {
                throw new IllegalStateException(propertyName + " cannot override database TLS mode outside dev/test");
            }
        }
    }

    private static String optionalProperty(ConfigurableEnvironment environment, String propertyName) {
        try {
            return environment.getProperty(propertyName);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void validateOptionalHikariUrl(ConfigurableEnvironment environment, String propertyName, Object value) {
        if (value == null) {
            return;
        }
        String url = value.toString();
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException(propertyName + " must not be blank outside dev/test");
        }
        String strippedUrl = url.strip();
        if (!allowsLocalSystemdStagingPlaintextMysqlUrl(environment, strippedUrl)) {
            validateVerifiedMysqlUrl(strippedUrl, propertyName);
        }
    }

    private static void validateVerifiedMysqlUrl(String url, String propertyName) {
        if (!url.startsWith("jdbc:mysql:")) {
            throw new IllegalStateException(propertyName + " must use the jdbc:mysql driver outside dev/test");
        }
        if (!hasVerifiedSslMode(url)) {
            throw new IllegalStateException(
                propertyName + " must set sslMode=VERIFY_CA or sslMode=VERIFY_IDENTITY outside dev/test"
            );
        }
    }

    private static boolean hasVerifiedSslMode(String url) {
        return hasAllowedSslMode(url, VERIFIED_SSL_MODES);
    }

    private static boolean allowsLocalSystemdStagingPlaintextMysqlUrl(ConfigurableEnvironment environment, String url) {
        return isLocalSystemdStaging(environment)
            && hasLoopbackMysqlHost(url)
            && hasAllowedSslMode(url, LOCAL_PLAINTEXT_SSL_MODES);
    }

    private static void applyLocalSystemdStagingDefaults(ConfigurableEnvironment environment) {
        if (!isLocalSystemdStaging(environment)) {
            return;
        }

        MutablePropertySources propertySources = environment.getPropertySources();
        if (propertySources.contains(LOCAL_SYSTEMD_STAGING_PROPERTY_SOURCE)) {
            return;
        }

        MapPropertySource propertySource =
            new MapPropertySource(LOCAL_SYSTEMD_STAGING_PROPERTY_SOURCE, LOCAL_SYSTEMD_STAGING_DEFAULTS);
        if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, propertySource);
        } else if (propertySources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, propertySource);
        } else {
            propertySources.addFirst(propertySource);
        }
    }

    private static boolean isLocalSystemdStaging(ConfigurableEnvironment environment) {
        String userDir = optionalProperty(environment, "user.dir");
        String invocationId = optionalProperty(environment, SYSTEMD_INVOCATION_ID_PROPERTY);
        return LOCAL_SYSTEMD_STAGING_WORKING_DIRECTORY.equals(userDir) && StringUtils.hasText(invocationId);
    }

    private static boolean hasAllowedSslMode(String url, Set<String> allowedSslModes) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) {
            return false;
        }
        String connectionPart = url.substring(0, queryStart);
        if (containsTlsModeProperty(canonicalize(decode(connectionPart)))) {
            return false;
        }

        String query = url.substring(queryStart + 1);
        String sslMode = null;
        for (String pair : query.split("[&;]")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = decode(pair.substring(0, separator)).toLowerCase(Locale.ROOT);
            if (!"sslmode".equals(name)) {
                if (HIKARI_TLS_MODE_PROPERTIES.contains(canonicalize(name))) {
                    return false;
                }
                continue;
            }
            if (sslMode != null) {
                return false;
            }
            sslMode = decode(pair.substring(separator + 1)).toLowerCase(Locale.ROOT);
        }
        return sslMode != null && allowedSslModes.contains(sslMode);
    }

    private static boolean hasLoopbackMysqlHost(String url) {
        if (!url.startsWith(MYSQL_SIMPLE_URL_PREFIX)) {
            return false;
        }

        int authorityStart = MYSQL_SIMPLE_URL_PREFIX.length();
        int authorityEnd = url.length();
        int pathStart = url.indexOf('/', authorityStart);
        if (pathStart >= 0) {
            authorityEnd = pathStart;
        }
        int queryStart = url.indexOf('?', authorityStart);
        if (queryStart >= 0 && queryStart < authorityEnd) {
            authorityEnd = queryStart;
        }

        String authority = url.substring(authorityStart, authorityEnd);
        if (!isSimpleMysqlAuthority(authority)) {
            return false;
        }

        String host = extractHost(authority);
        String decodedHost = decode(host).toLowerCase(Locale.ROOT);
        return "localhost".equals(decodedHost)
            || "127.0.0.1".equals(decodedHost)
            || "::1".equals(decodedHost)
            || "0:0:0:0:0:0:0:1".equals(decodedHost);
    }

    private static boolean isSimpleMysqlAuthority(String authority) {
        if (!StringUtils.hasText(authority)
            || authority.contains(",")
            || authority.contains("@")
            || authority.contains("%")
            || authority.startsWith("address=")) {
            return false;
        }

        if (authority.startsWith("[")) {
            int bracketEnd = authority.indexOf(']');
            if (bracketEnd <= 1) {
                return false;
            }
            String rest = authority.substring(bracketEnd + 1);
            return rest.isEmpty() || (rest.startsWith(":") && isPort(rest.substring(1)));
        }

        int firstColon = authority.indexOf(':');
        if (firstColon < 0) {
            return true;
        }
        if (authority.indexOf(':', firstColon + 1) >= 0) {
            return false;
        }
        return firstColon > 0 && isPort(authority.substring(firstColon + 1));
    }

    private static boolean isPort(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String extractHost(String authority) {
        if (authority.startsWith("[")) {
            int bracketEnd = authority.indexOf(']');
            if (bracketEnd > 1) {
                return authority.substring(1, bracketEnd);
            }
            return "";
        }

        int portStart = authority.indexOf(':');
        if (portStart < 0) {
            return authority;
        }
        return authority.substring(0, portStart);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8).strip();
    }

    private static boolean isHikariTlsModeProperty(String canonicalName) {
        if (!canonicalName.startsWith(HIKARI_DATA_SOURCE_PROPERTIES_PREFIX)) {
            return false;
        }
        return containsTlsModeProperty(canonicalName);
    }

    private static boolean containsTlsModeProperty(String canonicalName) {
        for (String tlsPropertyName : HIKARI_TLS_MODE_PROPERTIES) {
            if (canonicalName.contains(tlsPropertyName)) {
                return true;
            }
        }
        return false;
    }

    private static String canonicalize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                builder.append(Character.toLowerCase(ch));
            }
        }
        return builder.toString();
    }
}
