package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Fail-closed backstop for RBAC. Declarative {@code @RequirePermission} gives framework-run
 * enforcement, but an annotation only protects the methods that carry it — so on its own it just
 * turns "forgot the {@code requirePermission} line" into "forgot the annotation". These rules are
 * the presence check that makes the model default-deny: a new unguarded mutator fails the build
 * instead of shipping as a silent hole.
 *
 * <p>Pure reflection + Spring's classpath scanner — no Spring context, no database, and (unlike a
 * bytecode-analysis tool) no sensitivity to the compiled Java version.
 */
class RbacEnforcementArchTest {

    private static final String BASE = "ooo.klae.connex.backend";

    /**
     * Entity services whose every state-changing entry point must be permission-gated. Other
     * services authorize differently — explicit actor in Role/Share/Invite/Workspace member ops,
     * self-service in User/Workspace creation, system-internal in Notification/Audit — and are out
     * of scope for this rule.
     */
    private static final List<String> ENTITY_SERVICES = List.of(
        "CompanyService", "PersonService", "DealService", "ActivityService",
        "NoteService", "TaskService", "TagService", "PipelineService", "AttachmentService",
        "ConnectionService", "CustomFieldDefinitionService");

    /** Verb prefixes that denote a state-changing public method in these services. */
    private static final Pattern MUTATOR = Pattern.compile(
        "^(create|update|delete|add|remove|replace|close|reopen|complete|assign|change)[A-Z]?\\w*");

    @Test
    void every_mutating_entity_service_method_is_permission_guarded() throws Exception {
        List<String> violations = new ArrayList<>();
        for (String simpleName : ENTITY_SERVICES) {
            Class<?> service = Class.forName(BASE + ".services." + simpleName);
            for (Method method : service.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                if (!MUTATOR.matcher(method.getName()).matches()) {
                    continue;
                }
                if (!method.isAnnotationPresent(RequirePermission.class)) {
                    violations.add(simpleName + "." + method.getName() + "(...)");
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "State-changing entity-service methods missing @RequirePermission "
                + "(add the annotation, or if genuinely public-by-design, exempt it explicitly here): " + violations);
    }

    @Test
    void controllers_do_not_inject_mappers_directly() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<BeanDefinition> controllers = scanner.findCandidateComponents(BASE + ".controllers");

        assertTrue(controllers.size() >= 10,
            "Expected to scan the REST controllers but found " + controllers.size()
                + " — the scan is misconfigured and this guard would pass vacuously.");

        List<String> violations = new ArrayList<>();
        for (BeanDefinition definition : controllers) {
            try {
                Class<?> controller = Class.forName(definition.getBeanClassName());
                for (Field field : controller.getDeclaredFields()) {
                    if (field.getType().getName().contains(".mappers.")) {
                        violations.add(controller.getSimpleName() + "." + field.getName());
                    }
                }
            } catch (ClassNotFoundException e) {
                fail("Could not load controller " + definition.getBeanClassName() + ": " + e.getMessage());
            }
        }
        assertTrue(violations.isEmpty(),
            "Controllers must reach the database through services (where @RequirePermission lives), "
                + "not a mapper directly; these inject a mapper: " + violations);
    }
}
