package ooo.klae.connex.backend.seeder;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

final class SeederTestSupport {
    private SeederTestSupport() {
    }

    static MockEnvironment seederEnvironment() {
        return seederEnvironment(safeRepositoryProperties());
    }

    static MockEnvironment seederEnvironment(Map<String, Object> repositoryProperties) {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.seeder.enabled", "true")
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none")
            .withProperty("connex.tenancy.routing.mode", "single-database")
            .withProperty("connex.object-storage.legacy-migration.mode", "off");
        environment.getPropertySources().addLast(new MapPropertySource(
            "Config resource 'class path resource [application-seeder.yml]'",
            repositoryProperties
        ));
        environment.setActiveProfiles("seeder");
        return environment;
    }

    static Map<String, Object> safeRepositoryProperties() {
        Map<String, Object> properties = new LinkedHashMap<>(
            SeederStartupConfigurationValidator.REPOSITORY_FLYWAY_PROPERTIES
        );
        properties.put("spring.sql.init.mode", "never");
        properties.put(
            "spring.sql.init.data-locations",
            SeederStartupConfigurationValidator.PROJECT_SQL_INIT_DATA_LOCATION
        );
        properties.put(
            "mybatis.mapper-locations",
            SeederStartupConfigurationValidator.PROJECT_MYBATIS_MAPPER_LOCATIONS
        );
        properties.put(
            "mybatis.type-aliases-package",
            SeederStartupConfigurationValidator.PROJECT_MYBATIS_TYPE_ALIASES_PACKAGE
        );
        properties.put("mybatis.configuration.map-underscore-to-camel-case", true);
        return Map.copyOf(properties);
    }

}
