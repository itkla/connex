package ooo.klae.connex.backend.seeder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;

/**
 * Validates the complete seeder configuration without constructing application infrastructure.
 */
final class SeederStartupConfigurationValidator {

    static final String PROJECT_DRIVER = "com.mysql.cj.jdbc.Driver";
    static final String PROJECT_HIKARI_CONNECTION_INIT_SQL = "SET time_zone = '+00:00'";
    static final String PROJECT_MIGRATION_LOCATION = "classpath:db/migration";
    static final String PROJECT_MYBATIS_MAPPER_LOCATIONS = "classpath:mappers/*.xml";
    static final String PROJECT_MYBATIS_TYPE_ALIASES_PACKAGE =
        "ooo.klae.connex.backend.beans";
    static final String PROJECT_SQL_INIT_DATA_LOCATION =
        "optional:classpath:seeder-sql-init-canary.sql";

    private static final String REFUSAL_PREFIX = "Seeder refused: ";
    private static final String SEEDER = "seeder";
    private static final String PRODUCTION_DATABASE = "connex_pub";
    private static final int DEFAULT_MYSQL_PORT = 3306;
    private static final String DATASOURCE_URL_PROPERTY = "spring.datasource.url";
    private static final String HIKARI_JDBC_URL_PROPERTY = "spring.datasource.hikari.jdbc-url";
    private static final String FLYWAY_URL_PROPERTY = "spring.flyway.url";
    private static final String HIKARI_CONFIGURATION_FILE_PROPERTY = "hikaricp.configurationFile";
    private static final String HIKARI_DATA_SOURCE_PROPERTIES_PROPERTY =
        "spring.datasource.hikari.data-source-properties";
    private static final String FLYWAY_JDBC_PROPERTIES_PROPERTY =
        "spring.flyway.jdbc-properties";
    private static final String FLYWAY_DEFAULT_SCHEMA_PROPERTY =
        "spring.flyway.default-schema";
    private static final String FLYWAY_SCHEMAS_PROPERTY = "spring.flyway.schemas";
    private static final String FLYWAY_INIT_SQLS_PROPERTY = "spring.flyway.init-sqls";
    private static final String FLYWAY_LOCATIONS_PROPERTY = "spring.flyway.locations";
    private static final String FLYWAY_CALLBACK_LOCATIONS_PROPERTY =
        "spring.flyway.callback-locations";
    private static final String FLYWAY_PLACEHOLDERS_PROPERTY = "spring.flyway.placeholders";
    private static final String REPOSITORY_SEEDER_PROPERTY_SOURCE =
        "class path resource [application-seeder.yml]";
    private static final String REPOSITORY_APPLICATION_PROPERTY_SOURCE =
        "class path resource [application.yml]";
    private static final Map<String, String> REPOSITORY_SQL_INIT_PROPERTIES = Map.of(
        "spring.sql.init.mode",
        "never",
        "spring.sql.init.data-locations",
        PROJECT_SQL_INIT_DATA_LOCATION
    );
    private static final Map<String, String> REPOSITORY_MYBATIS_PROPERTIES = Map.of(
        "mybatis.mapper-locations",
        PROJECT_MYBATIS_MAPPER_LOCATIONS,
        "mybatis.type-aliases-package",
        PROJECT_MYBATIS_TYPE_ALIASES_PACKAGE,
        "mybatis.configuration.map-underscore-to-camel-case",
        "true"
    );
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
        "localhost",
        "127.0.0.1",
        "::1",
        "0:0:0:0:0:0:0:1"
    );
    private static final Set<String> SAFE_CONNECTOR_PROPERTY_KEYS = Set.of(
        "allowpublickeyretrieval",
        "cachedefaulttimezone",
        "cacheprepstmts",
        "cacheresultsetmetadata",
        "cacheserverconfiguration",
        "characterencoding",
        "charactersetresults",
        "clientcertificatekeystoretype",
        "clientcertificatekeystoreurl",
        "connectionattributes",
        "connectioncollation",
        "connectiontimezone",
        "connecttimeout",
        "createdatabaseifnotexist",
        "defaultfetchsize",
        "enabledsslciphersuites",
        "enabledtlsprotocols",
        "fallbacktosystemkeystore",
        "fallbacktosystemtruststore",
        "fipscompliantjsse",
        "forceconnectiontimezonetosession",
        "maintaintimestats",
        "metadatacachesize",
        "passwordcharacterencoding",
        "prepstmtcachesize",
        "prepstmtcachesqllimit",
        "preserveinstants",
        "requiressl",
        "rewritebatchedstatements",
        "sendfractionalseconds",
        "sendfractionalsecondsfortime",
        "servertimezone",
        "sockettimeout",
        "sslmode",
        "tcpkeepalive",
        "tcpnodelay",
        "tcprcvbuf",
        "tcpsndbuf",
        "tcptrafficclass",
        "tlsciphersuites",
        "tlsversions",
        "trustcertificatekeystoretype",
        "trustcertificatekeystoreurl",
        "useaffectedrows",
        "usecompression",
        "usecursorfetch",
        "useinformationschema",
        "usereadaheadinput",
        "useserverprepstmts",
        "usessl",
        "usestreamlengthsinprepstmts",
        "verifyservercertificate"
    ).stream().map(SeederStartupConfigurationValidator::normalizedConnectorPropertyName)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> EXPLICITLY_DENIED_CONNECTOR_PROPERTY_KEYS = Set.of(
        "address",
        "allowloadlocalinfile",
        "allowloadlocalinfileinpath",
        "allowmultiqueries",
        "allowurlinlocalinfile",
        "authenticationopenidconnectcallbackhandler",
        "authenticationplugins",
        "authenticationwebauthncallbackhandler",
        "autogeneratetestcasescript",
        "clientcertificatekeystorepassword",
        "clientinfoprovider",
        "connectionlifecycleinterceptors",
        "database",
        "databasename",
        "dbname",
        "defaultauthenticationplugin",
        "disabledauthenticationplugins",
        "dnssrv",
        "exceptioninterceptors",
        "ha.enablejmx",
        "ha.loadbalancestrategy",
        "haenablejmx",
        "haloadbalancestrategy",
        "host",
        "hostname",
        "idtokenfile",
        "jdbcurl",
        "keymanagerfactoryprovider",
        "keystoreprovider",
        "loadbalanceautocommitstatementregex",
        "loadbalanceautocommitstatementthreshold",
        "loadbalanceblocklisttimeout",
        "loadbalanceconnectiongroup",
        "loadbalanceexceptionchecker",
        "loadbalancehostremovalgraceperiod",
        "loadbalancepingtimeout",
        "loadbalancesqlexceptionsubclassfailover",
        "loadbalancesqlstatefailover",
        "loadbalancevalidateconnectiononswapserver",
        "localsocketaddress",
        "logger",
        "namedpipepath",
        "ociconfigfile",
        "ociconfigprofile",
        "parseinfocachefactory",
        "path",
        "port",
        "portnumber",
        "priority",
        "profilereventhandler",
        "propertiestransform",
        "protocol",
        "queryinfocachefactory",
        "queryinterceptors",
        "replicationconnectiongroup",
        "serveraffinityorder",
        "serverconfigcachefactory",
        "servername",
        "sessionvariables",
        "socketfactory",
        "socksproxyhost",
        "socksproxyport",
        "socksproxyremotedns",
        "sslcontextprovider",
        "trustcertificatekeystorepassword",
        "trustmanagerfactoryprovider",
        "type",
        "url",
        "useconfigs",
        "xdevapiasyncresponsetimeout",
        "xdevapiauth",
        "xdevapicompression",
        "xdevapicompressionalgorithms",
        "xdevapicompressionextensions",
        "xdevapiconnectionattributes",
        "xdevapiconnecttimeout",
        "xdevapidnssrv",
        "xdevapifallbacktosystemkeystore",
        "xdevapifallbacktosystemtruststore",
        "xdevapisslkeystore",
        "xdevapisslkeystorepassword",
        "xdevapisslkeystoretype",
        "xdevapisslmode",
        "xdevapissltruststore",
        "xdevapissltruststorepassword",
        "xdevapissltruststoretype",
        "xdevapitlsciphersuites",
        "xdevapitlsversions"
    ).stream().map(SeederStartupConfigurationValidator::normalizedConnectorPropertyName)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> REFUSED_HIKARI_SCALAR_PROPERTIES = Set.of(
        "spring.datasource.type",
        "spring.datasource.jndi-name",
        "spring.datasource.hikari.catalog",
        "spring.datasource.hikari.connection-test-query",
        "spring.datasource.hikari.credentials-provider",
        "spring.datasource.hikari.credentials-provider-class-name",
        "spring.datasource.hikari.data-source",
        "spring.datasource.hikari.data-source-class-name",
        "spring.datasource.hikari.data-source-jndi",
        "spring.datasource.hikari.exception-override",
        "spring.datasource.hikari.exception-override-class-name",
        "spring.datasource.hikari.health-check-registry",
        "spring.datasource.hikari.metric-registry",
        "spring.datasource.hikari.metrics-tracker-factory",
        "spring.datasource.hikari.scheduled-executor",
        "spring.datasource.hikari.schema",
        "spring.datasource.hikari.thread-factory"
    );
    private static final Set<String> REFUSED_OPERATOR_CLASS_PROPERTIES = Set.of(
        "context.initializer.classes",
        "context.listener.classes"
    );
    private static final Set<String> IGNORED_NON_ENUMERABLE_PROPERTY_SOURCES = Set.of(
        "configurationProperties",
        "random",
        "servletConfigInitParams",
        "servletContextInitParams"
    );

    private SeederStartupConfigurationValidator() {
    }

    static Optional<ValidatedConfiguration> validateIfActivated(
            ConfigurableEnvironment environment) {
        return validateIfActivated(environment, null);
    }

    static Optional<ValidatedConfiguration> validateIfActivated(
            ConfigurableEnvironment environment,
            SpringApplication application) {
        try {
            if (!hasSeederSignal(environment)) {
                return Optional.empty();
            }
            if (application != null
                    && application.getWebApplicationType() != WebApplicationType.NONE) {
                throw dedicatedLauncherRefusal();
            }
            verifyNoHikariConfigurationFile();
            PropertyAccess properties = new PropertyAccess(environment);
            return Optional.of(validateActivated(environment, properties));
        } catch (RuntimeException exception) {
            throw sanitizedRefusal(exception);
        }
    }

    static ValidatedConfiguration validate(Environment environment) {
        try {
            if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
                throw refused("the Spring Environment cannot be safely inspected");
            }
            if (!hasSeederSignal(configurableEnvironment)) {
                throw refused("no seeder activation signal is present");
            }
            verifyNoHikariConfigurationFile();
            PropertyAccess properties = new PropertyAccess(configurableEnvironment);
            return validateActivated(configurableEnvironment, properties);
        } catch (RuntimeException exception) {
            throw sanitizedRefusal(exception);
        }
    }

    static void verifyJdbcUrl(String url, boolean allowRemoteHost) {
        verifiedTarget(url, "JDBC URL", allowRemoteHost);
    }

    static boolean isAllowedConnectorProperty(String propertyName) {
        return SAFE_CONNECTOR_PROPERTY_KEYS.contains(
            normalizedConnectorPropertyName(propertyName)
        );
    }

    static boolean isExplicitlyDeniedConnectorProperty(String propertyName) {
        String normalizedName = normalizedConnectorPropertyName(propertyName);
        return EXPLICITLY_DENIED_CONNECTOR_PROPERTY_KEYS.contains(normalizedName)
            || normalizedName.startsWith("xdevapi")
            || normalizedName.startsWith("loadbalance");
    }

    static boolean hasSeederSignal(ConfigurableEnvironment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains(SEEDER)) {
            return true;
        }
        return hasSignalValue(environment, "connex.seeder.enabled", "true")
            || hasSignalValue(environment, "connex.maintenance.mode", SEEDER);
    }

    private static boolean hasSignalValue(
            ConfigurableEnvironment environment,
            String propertyName,
            String signalValue) {
        String canonicalName = canonicalPropertyName(propertyName);
        List<PropertySource<?>> enumerableSources = new ArrayList<>();
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (propertySource instanceof EnumerablePropertySource<?>) {
                enumerableSources.add(propertySource);
            }
        }
        PropertySourcesPlaceholdersResolver placeholdersResolver =
            new PropertySourcesPlaceholdersResolver(enumerableSources);
        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerablePropertySource)) {
                continue;
            }
            for (String candidateName : enumerablePropertySource.getPropertyNames()) {
                if (!canonicalName.equals(canonicalPropertyName(candidateName))) {
                    continue;
                }
                Object value = enumerablePropertySource.getProperty(candidateName);
                if (value instanceof OriginTrackedValue trackedValue) {
                    value = trackedValue.getValue();
                }
                if (value instanceof CharSequence
                        || value instanceof Number
                        || value instanceof Boolean
                        || value instanceof Character) {
                    Object resolved = placeholdersResolver.resolvePlaceholders(value.toString());
                    if (resolved instanceof String text && signalValue.equals(text)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ValidatedConfiguration validateActivated(
            ConfigurableEnvironment environment,
            PropertyAccess properties) {
        verifyExclusiveSeederProfile(environment);
        if (!properties.requiredBoolean("connex.seeder.enabled")) {
            throw refused("connex.seeder.enabled must be true");
        }
        properties.requireExactValue("connex.maintenance.mode", SEEDER);
        properties.requireExactValue("spring.main.web-application-type", "none");
        properties.requireExactValue(
            "connex.tenancy.routing.mode",
            "single-database"
        );
        String deploymentProfile = properties.optionalString("connex.deployment.profile");
        if (deploymentProfile != null && !deploymentProfile.isEmpty()) {
            throw refused("connex.deployment.profile must be unset");
        }
        properties.requireExactValue(
            "connex.object-storage.legacy-migration.mode",
            "off"
        );
        properties.requireClosedRepositoryNamespace(
            "spring.sql.init",
            REPOSITORY_SQL_INIT_PROPERTIES
        );
        properties.requireClosedRepositoryNamespace(
            "mybatis",
            REPOSITORY_MYBATIS_PROPERTIES
        );
        if (properties.hasCanonicalPrefix("spring.main.sources")) {
            throw refused("spring.main.sources must be unset");
        }
        for (String propertyName : REFUSED_OPERATOR_CLASS_PROPERTIES) {
            if (properties.hasRelaxedProperty(propertyName)) {
                throw refused(propertyName + " must be unset");
            }
        }
        boolean allowRemoteHost = properties.optionalBoolean(
            "connex.seeder.allow-remote-host",
            false
        );

        properties.refuseScalarDescendants(DATASOURCE_URL_PROPERTY);
        String baselineUrl = properties.requiredString(DATASOURCE_URL_PROPERTY);
        JdbcTarget baseline = verifiedTarget(
            baselineUrl,
            DATASOURCE_URL_PROPERTY,
            allowRemoteHost
        );

        verifyUrlOverrides(
            properties,
            baseline,
            HIKARI_JDBC_URL_PROPERTY,
            allowRemoteHost
        );
        verifyUrlOverrides(properties, baseline, FLYWAY_URL_PROPERTY, allowRemoteHost);
        verifyHikariConfiguration(properties);
        verifyConnectorProperties(
            properties.stringMap(HIKARI_DATA_SOURCE_PROPERTIES_PROPERTY),
            HIKARI_DATA_SOURCE_PROPERTIES_PROPERTY
        );
        verifyFlywayConfiguration(properties, baseline);
        return new ValidatedConfiguration(baseline, allowRemoteHost);
    }

    private static void verifyNoHikariConfigurationFile() {
        try {
            if (System.getProperty(HIKARI_CONFIGURATION_FILE_PROPERTY) != null) {
                throw refused(HIKARI_CONFIGURATION_FILE_PROPERTY + " must be unset");
            }
        } catch (SecurityException exception) {
            throw refused(HIKARI_CONFIGURATION_FILE_PROPERTY + " cannot be safely inspected");
        }
    }

    private static void verifyExclusiveSeederProfile(ConfigurableEnvironment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length != 1 || !SEEDER.equals(activeProfiles[0])) {
            throw refused("seeder must be the only active Spring profile");
        }
    }

    private static void verifyUrlOverrides(
            PropertyAccess properties,
            JdbcTarget baseline,
            String propertyName,
            boolean allowRemoteHost) {
        properties.refuseScalarDescendants(propertyName);
        List<String> configuredUrls = properties.relaxedScalarAliasValues(propertyName);
        String agreedUrl = null;
        for (String configuredUrl : configuredUrls) {
            if (!StringUtils.hasText(configuredUrl)) {
                throw refused(propertyName + " must be unset or nonblank");
            }
            if (agreedUrl != null && !agreedUrl.strip().equals(configuredUrl.strip())) {
                throw refused(propertyName + " has conflicting relaxed aliases");
            }
            agreedUrl = configuredUrl;
            JdbcTarget candidate = verifiedTarget(
                configuredUrl,
                propertyName,
                allowRemoteHost
            );
            verifySameTarget(baseline, candidate, propertyName);
        }
    }

    private static void verifyHikariConfiguration(PropertyAccess properties) {
        for (String propertyName : REFUSED_HIKARI_SCALAR_PROPERTIES) {
            if (properties.hasRelaxedProperty(propertyName)) {
                throw refused(propertyName + " must be unset");
            }
        }
        if (properties.hasCanonicalPrefix("spring.datasource.xa")) {
            throw refused("spring.datasource.xa configuration must be unset");
        }
        verifyOptionalDriver(properties, "spring.datasource.driver-class-name");
        verifyOptionalDriver(properties, "spring.datasource.hikari.driver-class-name");

        String connectionInitSql = properties.optionalString(
            "spring.datasource.hikari.connection-init-sql"
        );
        if (connectionInitSql != null
                && !PROJECT_HIKARI_CONNECTION_INIT_SQL.equals(connectionInitSql.strip())) {
            throw refused(
                "spring.datasource.hikari.connection-init-sql must be unset or the repository UTC initializer"
            );
        }
    }

    private static void verifyFlywayConfiguration(
            PropertyAccess properties,
            JdbcTarget baseline) {
        verifyOptionalDriver(properties, "spring.flyway.driver-class-name");
        verifyConnectorProperties(
            properties.stringMap(FLYWAY_JDBC_PROPERTIES_PROPERTY),
            FLYWAY_JDBC_PROPERTIES_PROPERTY
        );

        properties.refuseScalarDescendants(FLYWAY_DEFAULT_SCHEMA_PROPERTY);
        verifySchema(
            properties.optionalString(FLYWAY_DEFAULT_SCHEMA_PROPERTY),
            FLYWAY_DEFAULT_SCHEMA_PROPERTY,
            baseline.database()
        );
        for (String schema : properties.stringList(FLYWAY_SCHEMAS_PROPERTY)) {
            verifySchema(schema, FLYWAY_SCHEMAS_PROPERTY, baseline.database());
        }

        if (!properties.stringList(FLYWAY_INIT_SQLS_PROPERTY).isEmpty()) {
            throw refused(FLYWAY_INIT_SQLS_PROPERTY + " must be empty");
        }
        if (!properties.stringMap(FLYWAY_PLACEHOLDERS_PROPERTY).isEmpty()
                || properties.hasRelaxedProperty(FLYWAY_PLACEHOLDERS_PROPERTY)) {
            throw refused(FLYWAY_PLACEHOLDERS_PROPERTY + " must be empty");
        }
        if (properties.optionalBoolean("spring.flyway.placeholder-replacement", true)) {
            throw refused("spring.flyway.placeholder-replacement must be false");
        }

        if (properties.hasCanonicalDescendant(FLYWAY_LOCATIONS_PROPERTY)) {
            throw refused(FLYWAY_LOCATIONS_PROPERTY + " cannot contain indexed additions");
        }
        properties.requireRepositoryControl(FLYWAY_LOCATIONS_PROPERTY);
        if (!PROJECT_MIGRATION_LOCATION.equals(
                properties.requiredString(FLYWAY_LOCATIONS_PROPERTY))) {
            throw refused(
                FLYWAY_LOCATIONS_PROPERTY + " must be exactly the repository migration location"
            );
        }
        if (properties.hasCanonicalDescendant(FLYWAY_CALLBACK_LOCATIONS_PROPERTY)) {
            throw refused(
                FLYWAY_CALLBACK_LOCATIONS_PROPERTY + " cannot contain indexed additions"
            );
        }
        properties.requireRepositoryControl(FLYWAY_CALLBACK_LOCATIONS_PROPERTY);
        if (!properties.stringList(FLYWAY_CALLBACK_LOCATIONS_PROPERTY).isEmpty()) {
            throw refused(
                FLYWAY_CALLBACK_LOCATIONS_PROPERTY + " must be empty"
            );
        }
    }

    private static void verifyOptionalDriver(PropertyAccess properties, String propertyName) {
        properties.refuseScalarDescendants(propertyName);
        String driver = properties.optionalString(propertyName);
        if (driver != null && !PROJECT_DRIVER.equals(driver)) {
            throw refused(propertyName + " must be unset or use the repository MySQL driver");
        }
    }

    private static void verifyConnectorProperties(
            Map<String, String> configuredProperties,
            String propertyName) {
        Set<String> configuredKeys = new LinkedHashSet<>();
        for (String key : configuredProperties.keySet()) {
            if (!isAllowedConnectorProperty(key)) {
                throw refused(propertyName + " contains an unreviewed Connector/J property");
            }
            if (!configuredKeys.add(canonicalConnectorPropertyKey(key))) {
                throw refused(propertyName + " contains ambiguous Connector/J aliases");
            }
        }
    }

    private static void verifySchema(
            String configuredSchema,
            String propertyName,
            String targetDatabase) {
        if (!StringUtils.hasText(configuredSchema)) {
            return;
        }
        if (!configuredSchema.equals(targetDatabase)) {
            throw refused(propertyName + " must name only the exact target catalog");
        }
    }

    static JdbcTarget verifiedTarget(
            String url,
            String propertyName,
            boolean allowRemoteHost) {
        JdbcTarget target = parse(url, propertyName);
        if (PRODUCTION_DATABASE.equalsIgnoreCase(target.database())) {
            throw refused(propertyName + " names the protected connex_pub catalog");
        }
        if (!LOOPBACK_HOSTS.contains(target.host().toLowerCase(Locale.ROOT))
                && !allowRemoteHost) {
            throw refused(
                propertyName + " uses a remote host without connex.seeder.allow-remote-host=true"
            );
        }
        return target;
    }

    private static JdbcTarget parse(String url, String propertyName) {
        if (!StringUtils.hasText(url)
                || !url.equals(url.strip())
                || !url.startsWith("jdbc:mysql://")) {
            throw refused(propertyName + " must use a simple jdbc:mysql:// URL");
        }
        try {
            URI uri = URI.create(url.substring("jdbc:".length()));
            String rawAuthority = uri.getRawAuthority();
            String rawPath = uri.getRawPath();
            if (!"mysql".equals(uri.getScheme())
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || !isSimpleAuthority(rawAuthority)
                    || !StringUtils.hasText(uri.getHost())
                    || !isSimpleCatalogPath(rawPath)) {
                throw refused(
                    propertyName + " must name one unambiguous host, port, and catalog"
                );
            }
            int port = uri.getPort();
            if (port == -1) {
                port = DEFAULT_MYSQL_PORT;
            } else if (port < 1 || port > 65_535) {
                throw refused(propertyName + " contains an invalid port");
            }
            String database = decode(rawPath.substring(1), propertyName);
            if (!StringUtils.hasText(database)
                    || !database.equals(database.strip())
                    || containsCatalogSeparator(database)) {
                throw refused(propertyName + " contains an invalid catalog name");
            }
            verifyQuery(uri.getRawQuery(), propertyName);
            return new JdbcTarget(unbracketed(uri.getHost()), port, database);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw refused(propertyName + " is malformed");
        }
    }

    private static boolean isSimpleAuthority(String rawAuthority) {
        if (!StringUtils.hasText(rawAuthority)
                || rawAuthority.contains(",")
                || rawAuthority.contains("@")
                || rawAuthority.contains("%")
                || rawAuthority.startsWith("address=")
                || rawAuthority.endsWith(":")) {
            return false;
        }
        if (rawAuthority.startsWith("[")) {
            int bracketEnd = rawAuthority.indexOf(']');
            if (bracketEnd <= 1) {
                return false;
            }
            String remainder = rawAuthority.substring(bracketEnd + 1);
            return remainder.isEmpty()
                || remainder.matches(":\\d{1,5}");
        }
        int firstColon = rawAuthority.indexOf(':');
        return firstColon < 0
            || firstColon == rawAuthority.lastIndexOf(':')
                && firstColon > 0
                && rawAuthority.substring(firstColon + 1).matches("\\d{1,5}");
    }

    private static boolean isSimpleCatalogPath(String rawPath) {
        return StringUtils.hasText(rawPath)
            && rawPath.startsWith("/")
            && rawPath.length() > 1
            && rawPath.indexOf('/', 1) < 0;
    }

    private static boolean containsCatalogSeparator(String database) {
        for (int index = 0; index < database.length(); index++) {
            char candidate = database.charAt(index);
            if (candidate == '/'
                    || candidate == '\\'
                    || candidate == '?'
                    || candidate == '#'
                    || Character.isISOControl(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static void verifyQuery(String rawQuery, String propertyName) {
        if (rawQuery == null) {
            return;
        }
        if (!StringUtils.hasText(rawQuery)) {
            throw refused(propertyName + " contains an empty query");
        }
        Set<String> seenKeys = new LinkedHashSet<>();
        for (String parameter : rawQuery.split("[&;]", -1)) {
            int separator = parameter.indexOf('=');
            if (separator <= 0 || separator == parameter.length() - 1) {
                throw refused(propertyName + " contains a malformed query parameter");
            }
            String key = decode(parameter.substring(0, separator), propertyName).strip();
            String value = decode(parameter.substring(separator + 1), propertyName);
            String normalizedKey = canonicalConnectorPropertyKey(key);
            if (!StringUtils.hasText(value)
                    || !isAllowedConnectorProperty(key)) {
                throw refused(propertyName + " contains an unreviewed Connector/J query property");
            }
            if (!seenKeys.add(normalizedKey)) {
                throw refused(propertyName + " contains an ambiguous duplicate query property");
            }
        }
    }

    private static String decode(String value, String propertyName) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw refused(propertyName + " contains malformed URL encoding");
        }
    }

    private static void verifySameTarget(
            JdbcTarget baseline,
            JdbcTarget candidate,
            String propertyName) {
        if (!baseline.matches(candidate)) {
            throw refused(propertyName + " disagrees with " + DATASOURCE_URL_PROPERTY);
        }
    }

    private static String normalizedPropertyKey(String key) {
        StringBuilder normalized = new StringBuilder(key.length());
        key.codePoints()
            .filter(Character::isLetterOrDigit)
            .map(Character::toLowerCase)
            .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private static String canonicalConnectorPropertyKey(String key) {
        return switch (normalizedConnectorPropertyName(key)) {
            case "servertimezone" -> "connectiontimezone";
            case "enabledsslciphersuites" -> "tlsciphersuites";
            case "enabledtlsprotocols" -> "tlsversions";
            default -> normalizedConnectorPropertyName(key);
        };
    }

    private static String normalizedConnectorPropertyName(String propertyName) {
        return propertyName.toLowerCase(Locale.ROOT);
    }

    private static String canonicalPropertyName(String propertyName) {
        return normalizedPropertyKey(propertyName);
    }

    private static String unbracketed(String host) {
        if (host != null && host.length() > 1 && host.charAt(0) == '['
                && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    static IllegalStateException refused(String reason) {
        return new SeederRefusalException(reason);
    }

    static IllegalStateException cleanRefusal(
            RuntimeException exception,
            String unexpectedReason) {
        if (exception instanceof SeederRefusalException seederRefusalException) {
            return refused(seederRefusalException.reason());
        }
        return refused(unexpectedReason);
    }

    static IllegalStateException dedicatedLauncherRefusal() {
        return refused("seeder signals require the dedicated non-web SeederApplication launcher");
    }

    private static IllegalStateException sanitizedRefusal(RuntimeException exception) {
        return cleanRefusal(
            exception,
            "activated configuration cannot be safely inspected"
        );
    }

    record ValidatedConfiguration(JdbcTarget target, boolean allowRemoteHost) {
    }

    record JdbcTarget(String host, int port, String database) {

        boolean matches(JdbcTarget other) {
            return host.equalsIgnoreCase(other.host)
                && port == other.port
                && database.equals(other.database);
        }
    }

    private static final class SeederRefusalException extends IllegalStateException {

        private final String reason;

        private SeederRefusalException(String reason) {
            super(REFUSAL_PREFIX + reason);
            this.reason = reason;
        }

        private String reason() {
            return reason;
        }
    }

    private static final class PropertyAccess {

        private final List<EnumerablePropertySource<?>> propertySources;
        private final PropertySourcesPlaceholdersResolver placeholdersResolver;
        private final Binder binder;

        private PropertyAccess(ConfigurableEnvironment environment) {
            List<PropertySource<?>> safeSources = new ArrayList<>();
            List<EnumerablePropertySource<?>> enumerableSources = new ArrayList<>();
            for (PropertySource<?> propertySource : environment.getPropertySources()) {
                if (propertySource instanceof EnumerablePropertySource<?> enumerablePropertySource) {
                    safeSources.add(propertySource);
                    enumerableSources.add(enumerablePropertySource);
                } else if (!IGNORED_NON_ENUMERABLE_PROPERTY_SOURCES.contains(
                        propertySource.getName())) {
                    throw refused(
                        "activated configuration contains an unsupported non-enumerable property source"
                    );
                }
            }
            this.propertySources = List.copyOf(enumerableSources);
            this.placeholdersResolver = new PropertySourcesPlaceholdersResolver(safeSources);
            this.binder = new Binder(
                ConfigurationPropertySources.from(safeSources),
                placeholdersResolver
            );
        }

        private String requiredString(String propertyName) {
            String value = optionalString(propertyName);
            if (!StringUtils.hasText(value)) {
                throw refused(propertyName + " is required");
            }
            return value;
        }

        private String optionalString(String propertyName) {
            refuseScalarDescendants(propertyName);
            List<String> relaxedValues = relaxedScalarAliasValues(propertyName);
            if (!relaxedValues.isEmpty()) {
                String value = relaxedValues.getFirst();
                for (String candidate : relaxedValues) {
                    if (!value.equals(candidate)) {
                        throw refused(propertyName + " has conflicting relaxed aliases");
                    }
                }
                return value;
            }
            String value = bind(propertyName, Bindable.of(String.class)).orElse(null);
            verifyResolved(value, propertyName);
            return value;
        }

        private boolean requiredBoolean(String propertyName) {
            String value = optionalString(propertyName);
            if (value == null) {
                throw refused(propertyName + " is required");
            }
            return parsedBoolean(value, propertyName);
        }

        private boolean optionalBoolean(String propertyName, boolean defaultValue) {
            String value = optionalString(propertyName);
            return value == null ? defaultValue : parsedBoolean(value, propertyName);
        }

        private List<String> stringList(String propertyName) {
            List<String> values = bind(propertyName, Bindable.listOf(String.class))
                .orElseGet(List::of);
            for (String value : values) {
                if (value == null) {
                    throw refused(propertyName + " is malformed or unbindable");
                }
                verifyResolved(value, propertyName);
            }
            return List.copyOf(values);
        }

        private Map<String, String> stringMap(String propertyName) {
            Map<String, String> values = bind(
                propertyName,
                Bindable.mapOf(String.class, String.class)
            ).orElseGet(Map::of);
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw refused(propertyName + " is malformed or unbindable");
                }
                verifyResolved(entry.getKey(), propertyName);
                verifyResolved(entry.getValue(), propertyName);
                copy.put(entry.getKey(), entry.getValue());
            }
            return Map.copyOf(copy);
        }

        private void requireExactValue(String propertyName, String requiredValue) {
            if (!requiredValue.equals(requiredString(propertyName))) {
                throw refused(propertyName + " must be " + requiredValue);
            }
        }

        private void requireRepositoryControl(String propertyName) {
            String canonicalName = canonicalPropertyName(propertyName);
            for (EnumerablePropertySource<?> propertySource : propertySources) {
                boolean sourceContainsProperty = Arrays.stream(propertySource.getPropertyNames())
                    .map(SeederStartupConfigurationValidator::canonicalPropertyName)
                    .anyMatch(canonicalName::equals);
                if (!sourceContainsProperty) {
                    continue;
                }
                if (!propertySource.getName().contains(REPOSITORY_SEEDER_PROPERTY_SOURCE)) {
                    throw refused(propertyName + " cannot be overridden by operator configuration");
                }
                return;
            }
            throw refused(propertyName + " must be repository-controlled");
        }

        private void requireClosedRepositoryNamespace(
                String namespace,
                Map<String, String> requiredProperties) {
            String canonicalNamespace = canonicalPropertyName(namespace);
            Map<String, String> requiredByCanonicalName = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : requiredProperties.entrySet()) {
                String previous = requiredByCanonicalName.put(
                    canonicalPropertyName(entry.getKey()),
                    entry.getValue()
                );
                if (previous != null) {
                    throw refused(namespace + " repository policy is ambiguous");
                }
            }
            Map<String, String> effectiveSources = new LinkedHashMap<>();
            Set<String> sourceProperties = new LinkedHashSet<>();
            for (EnumerablePropertySource<?> propertySource : propertySources) {
                String sourceName = propertySource.getName();
                for (String candidateName : propertySource.getPropertyNames()) {
                    String canonicalCandidate = canonicalPropertyName(candidateName);
                    if (!canonicalCandidate.startsWith(canonicalNamespace)) {
                        continue;
                    }
                    String requiredValue = requiredByCanonicalName.get(canonicalCandidate);
                    if (requiredValue == null) {
                        throw refused(namespace + " contains an unsupported property");
                    }
                    if (!isRepositoryApplicationPropertySource(sourceName)) {
                        throw refused(
                            namespace + " cannot be overridden by operator configuration"
                        );
                    }
                    String sourceProperty = sourceName + '\0' + canonicalCandidate;
                    if (!sourceProperties.add(sourceProperty)) {
                        throw refused(namespace + " contains ambiguous relaxed aliases");
                    }
                    String configuredValue = resolvedScalar(
                        propertySource.getProperty(candidateName),
                        candidateName
                    );
                    if (!requiredValue.equals(configuredValue)) {
                        throw refused(candidateName + " must use the repository value");
                    }
                    effectiveSources.putIfAbsent(canonicalCandidate, sourceName);
                }
            }
            for (String requiredProperty : requiredProperties.keySet()) {
                String effectiveSource = effectiveSources.get(
                    canonicalPropertyName(requiredProperty)
                );
                if (effectiveSource == null) {
                    throw refused(requiredProperty + " must be repository-controlled");
                }
                if (!effectiveSource.contains(REPOSITORY_SEEDER_PROPERTY_SOURCE)) {
                    throw refused(
                        requiredProperty + " must be pinned by application-seeder.yml"
                    );
                }
            }
        }

        private static boolean isRepositoryApplicationPropertySource(String sourceName) {
            return sourceName.contains(REPOSITORY_SEEDER_PROPERTY_SOURCE)
                || sourceName.contains(REPOSITORY_APPLICATION_PROPERTY_SOURCE);
        }

        private boolean hasRelaxedProperty(String propertyName) {
            String canonicalName = canonicalPropertyName(propertyName);
            return propertySources.stream().anyMatch(source ->
                Arrays.stream(source.getPropertyNames())
                    .map(SeederStartupConfigurationValidator::canonicalPropertyName)
                    .anyMatch(canonicalName::equals)
            );
        }

        private boolean hasCanonicalPrefix(String propertyPrefix) {
            String canonicalPrefix = canonicalPropertyName(propertyPrefix);
            return propertySources.stream().anyMatch(source ->
                Arrays.stream(source.getPropertyNames())
                    .map(SeederStartupConfigurationValidator::canonicalPropertyName)
                    .anyMatch(name -> name.equals(canonicalPrefix)
                        || name.startsWith(canonicalPrefix))
            );
        }

        private void refuseScalarDescendants(String propertyName) {
            if (hasCanonicalDescendant(propertyName)) {
                throw refused(propertyName + " must be a scalar");
            }
        }

        private boolean hasCanonicalDescendant(String propertyName) {
            String canonicalName = canonicalPropertyName(propertyName);
            return propertySources.stream().anyMatch(source ->
                Arrays.stream(source.getPropertyNames())
                    .map(SeederStartupConfigurationValidator::canonicalPropertyName)
                    .anyMatch(candidate ->
                        candidate.startsWith(canonicalName) && !candidate.equals(canonicalName))
            );
        }

        private List<String> relaxedScalarAliasValues(String propertyName) {
            String canonicalName = canonicalPropertyName(propertyName);
            Map<String, String> valuesByAlias = new LinkedHashMap<>();
            for (EnumerablePropertySource<?> propertySource : propertySources) {
                for (String candidateName : propertySource.getPropertyNames()) {
                    if (!canonicalName.equals(canonicalPropertyName(candidateName))) {
                        continue;
                    }
                    if (!valuesByAlias.containsKey(candidateName)) {
                        Object rawValue = propertySource.getProperty(candidateName);
                        valuesByAlias.put(
                            candidateName,
                            resolvedScalar(rawValue, propertyName)
                        );
                    }
                }
            }
            return List.copyOf(valuesByAlias.values());
        }

        private String resolvedScalar(Object rawValue, String propertyName) {
            Object value = rawValue;
            if (value instanceof OriginTrackedValue trackedValue) {
                value = trackedValue.getValue();
            }
            if (!(value instanceof CharSequence)
                    && !(value instanceof Number)
                    && !(value instanceof Boolean)
                    && !(value instanceof Character)) {
                throw refused(propertyName + " must be a scalar");
            }
            Object resolved = placeholdersResolver.resolvePlaceholders(value.toString());
            if (!(resolved instanceof String text)) {
                throw refused(propertyName + " cannot be safely resolved");
            }
            verifyResolved(text, propertyName);
            return text;
        }

        private <T> Optional<T> bind(String propertyName, Bindable<T> bindable) {
            try {
                org.springframework.boot.context.properties.bind.BindResult<T> result =
                    binder.bind(propertyName, bindable);
                return result.isBound() ? Optional.of(result.get()) : Optional.empty();
            } catch (RuntimeException exception) {
                throw refused(propertyName + " is malformed or unbindable");
            }
        }

        private static void verifyResolved(String value, String propertyName) {
            if (value != null && value.contains("${")) {
                throw refused(propertyName + " contains an unresolved placeholder");
            }
        }

        private static boolean parsedBoolean(String value, String propertyName) {
            return switch (value) {
                case "true" -> true;
                case "false" -> false;
                default -> throw refused(propertyName + " is malformed or unbindable");
            };
        }
    }
}
