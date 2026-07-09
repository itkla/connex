package ooo.klae.connex.backend.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * Fails deployed startup before datasource creation when the database transport is not verified TLS.
 */
public class DatabaseTransportSecurityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Set<String> VERIFIED_SSL_MODES = Set.of("verify_ca", "verify_identity");
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
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            return;
        }

        String url = requiredProperty(environment, "spring.datasource.url", "CONNEX_DB_URL");
        requiredProperty(environment, "spring.datasource.username", "CONNEX_DB_USERNAME");
        requiredProperty(environment, "spring.datasource.password", "CONNEX_DB_PASSWORD");

        validateVerifiedMysqlUrl(url, "CONNEX_DB_URL");
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
                        validateOptionalHikariUrl(propertyName, propertySource.getProperty(propertyName));
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
                validateOptionalHikariUrl(propertyName, optionalProperty(environment, propertyName));
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

    private static void validateOptionalHikariUrl(String propertyName, Object value) {
        if (value == null) {
            return;
        }
        String url = value.toString();
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException(propertyName + " must not be blank outside dev/test");
        }
        validateVerifiedMysqlUrl(url.strip(), propertyName);
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
        return sslMode != null && VERIFIED_SSL_MODES.contains(sslMode);
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
