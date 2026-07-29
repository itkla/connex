package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConnectorJSeederPropertyPolicyCharacterizationTest {

    @Test
    void connectorJ97CanonicalAndCompatibilityNamesStayClassified() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Class<?> propertyKeyClass = Class.forName(
            "com.mysql.cj.conf.PropertyKey",
            false,
            classLoader
        );
        assertEquals("9.7.0", propertyKeyClass.getPackage().getImplementationVersion());

        Method valuesMethod = propertyKeyClass.getMethod("values");
        Method keyNameMethod = propertyKeyClass.getMethod("getKeyName");
        Method aliasMethod = propertyKeyClass.getMethod("getCcAlias");
        Method fromValueMethod = propertyKeyClass.getMethod("fromValue", String.class);
        Object values = valuesMethod.invoke(null);
        Map<String, String> aliasesByCanonicalName = new LinkedHashMap<>();
        for (int index = 0; index < Array.getLength(values); index++) {
            Object propertyKey = Array.get(values, index);
            String canonicalName = (String) keyNameMethod.invoke(propertyKey);
            String alias = (String) aliasMethod.invoke(propertyKey);
            if (alias != null) {
                aliasesByCanonicalName.put(canonicalName, alias);
            }
        }

        assertEquals("namedPipePath", aliasesByCanonicalName.get("path"));
        assertEquals(
            "parseInfoCacheFactory",
            aliasesByCanonicalName.get("queryInfoCacheFactory")
        );
        assertEquals("serverTimezone", aliasesByCanonicalName.get("connectionTimeZone"));
        assertEquals("cacheDefaultTimezone", aliasesByCanonicalName.get("cacheDefaultTimeZone"));
        assertEquals("enabledSSLCipherSuites", aliasesByCanonicalName.get("tlsCiphersuites"));
        assertEquals("enabledTLSProtocols", aliasesByCanonicalName.get("tlsVersions"));

        for (String deniedName : new String[] {
            "clientCertificateKeyStoreUrl",
            "clientCertificateKeyStorePassword",
            "connectionAttributes",
            "path",
            "namedPipePath",
            "queryInfoCacheFactory",
            "parseInfoCacheFactory",
            "ha.loadBalanceStrategy",
            "haLoadBalanceStrategy",
            "trustCertificateKeyStoreUrl",
            "trustCertificateKeyStorePassword",
            "xdevapi.ssl-mode",
            "xdevapiSslMode"
        }) {
            assertTrue(
                SeederStartupConfigurationValidator.isExplicitlyDeniedConnectorProperty(
                    deniedName
                ),
                deniedName
            );
            assertFalse(
                SeederStartupConfigurationValidator.isAllowedConnectorProperty(deniedName),
                deniedName
            );
            assertTrue(fromValueMethod.invoke(null, deniedName) != null, deniedName);
        }

        for (String allowedName : new String[] {
            "connectionTimeZone",
            "serverTimezone",
            "cacheDefaultTimeZone",
            "cacheDefaultTimezone",
            "tlsCiphersuites",
            "enabledSSLCipherSuites",
            "tlsVersions",
            "enabledTLSProtocols",
            "connectTimeout"
        }) {
            assertTrue(
                SeederStartupConfigurationValidator.isAllowedConnectorProperty(allowedName),
                allowedName
            );
            assertFalse(
                SeederStartupConfigurationValidator.isExplicitlyDeniedConnectorProperty(
                    allowedName
                ),
                allowedName
            );
            assertTrue(fromValueMethod.invoke(null, allowedName) != null, allowedName);
        }
    }
}
