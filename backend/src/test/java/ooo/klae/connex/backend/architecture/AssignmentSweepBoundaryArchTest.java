package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import ooo.klae.connex.backend.mappers.AssignmentSweepMapper;
import ooo.klae.connex.backend.services.UserAccountCatalogOffboardingService;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/** Confines assignment maintenance persistence to the catalog offboarding boundary. */
class AssignmentSweepBoundaryArchTest {
    @Test
    void everyAssignmentMaintenanceStatementHasAnExactTenantExemption() throws Exception {
        assertTrue(TenantScopeInterceptor.SCOPED_NAMESPACES.contains(AssignmentSweepMapper.class.getName()));
        Field field = TenantScopeInterceptor.class.getDeclaredField("EXEMPT_STATEMENTS");
        field.setAccessible(true);
        Set<?> exemptions = (Set<?>) field.get(null);
        for (var method : AssignmentSweepMapper.class.getDeclaredMethods()) {
            String statement = AssignmentSweepMapper.class.getName() + "." + method.getName();
            assertTrue(exemptions.contains(statement), statement);
        }
    }

    @Test
    void onlyCatalogOffboardingCanUseAssignmentMaintenance() throws Exception {
        Set<String> injectors = new HashSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadata, factory) -> Files.exists(Path.of("src/main/java")
            .resolve(metadata.getClassMetadata().getClassName().split("\\$")[0].replace('.', '/') + ".java")));
        for (BeanDefinition definition : scanner.findCandidateComponents("ooo.klae.connex.backend")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == AssignmentSweepMapper.class) {
                    injectors.add(type.getName());
                }
            }
            for (var constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    if (parameter == AssignmentSweepMapper.class) {
                        injectors.add(type.getName());
                    }
                }
            }
        }
        assertEquals(Set.of(UserAccountCatalogOffboardingService.class.getName()), injectors);
    }
}
