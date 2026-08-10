package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

/**
 * Keeps support-journal attribution on the reviewed current-tenant controller surface.
 */
class TenantJournalAttributionArchTest {
    private static final String BACKEND_PACKAGE = "ooo.klae.connex.backend";
    private static final Set<String> ATTRIBUTABLE_CONTROLLERS = Set.of(
        "ActivityController",
        "CompanyController",
        "DealController",
        "NoteController",
        "PersonController",
        "TaskController");

    @Test
    void onlyReviewedCurrentTenantHandlersAreJournalAttributable() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<BeanDefinition> definitions = scanner.findCandidateComponents(BACKEND_PACKAGE);

        Set<String> actual = new TreeSet<>();
        List<String> explicitTenantTargets = new ArrayList<>();
        for (BeanDefinition definition : definitions) {
            Class<?> controller = load(definition);
            boolean classAttributable =
                AnnotatedElementUtils.hasAnnotation(controller, TenantJournalAttributable.class);
            if (classAttributable) {
                actual.add(controller.getSimpleName());
                recordExplicitTarget(controller.getSimpleName(), controller, explicitTenantTargets);
            }
            for (Method method : controller.getDeclaredMethods()) {
                boolean methodAttributable =
                    AnnotatedElementUtils.hasAnnotation(method, TenantJournalAttributable.class);
                if (methodAttributable) {
                    actual.add(controller.getSimpleName());
                }
                if (classAttributable || methodAttributable) {
                    recordExplicitTarget(
                        controller.getSimpleName() + "." + method.getName(),
                        method,
                        explicitTenantTargets);
                }
            }
        }

        assertEquals(ATTRIBUTABLE_CONTROLLERS, actual,
            "Support-journal attribution changed without review of its tenant-boundary contract");
        assertTrue(explicitTenantTargets.isEmpty(),
            "Journal-attributable handlers must not carry explicit organization/workspace targets: "
                + explicitTenantTargets);
    }

    private static Class<?> load(BeanDefinition definition) {
        try {
            return Class.forName(definition.getBeanClassName());
        } catch (ClassNotFoundException exception) {
            return fail("Could not load controller " + definition.getBeanClassName(), exception);
        }
    }

    private static void recordExplicitTarget(
            String handler, AnnotatedElement element, List<String> targets) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        if (mapping == null) {
            return;
        }
        Stream.concat(Arrays.stream(mapping.path()), Arrays.stream(mapping.value()))
            .filter(TenantJournalAttributionArchTest::isExplicitTenantTarget)
            .map(path -> handler + "=" + path)
            .forEach(targets::add);
    }

    private static boolean isExplicitTenantTarget(String path) {
        return path.contains("{orgId}") || path.contains("{workspaceId}");
    }
}
