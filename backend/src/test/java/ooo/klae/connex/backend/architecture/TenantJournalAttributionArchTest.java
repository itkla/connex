package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import ooo.klae.connex.backend.tenant.TenantJournalAttributable;
import ooo.klae.connex.backend.tenant.TenantJournalClientDriven;

/**
 * Keeps support-journal attribution on the reviewed current-tenant controller surface.
 */
class TenantJournalAttributionArchTest {
    private static final String BACKEND_PACKAGE = "ooo.klae.connex.backend";
    private static final List<String> ATTRIBUTABLE_CONTROLLERS = List.of(
        "ooo.klae.connex.backend.controllers.ActivityController",
        "ooo.klae.connex.backend.controllers.AiAssistantController",
        "ooo.klae.connex.backend.controllers.AiAssistantProactiveController",
        "ooo.klae.connex.backend.controllers.AiAssistantSkillController",
        "ooo.klae.connex.backend.controllers.CompanyController",
        "ooo.klae.connex.backend.controllers.DealController",
        "ooo.klae.connex.backend.controllers.LegacyRecordCreationController",
        "ooo.klae.connex.backend.controllers.NoteController",
        "ooo.klae.connex.backend.controllers.PersonController",
        "ooo.klae.connex.backend.controllers.RecordCommentController",
        "ooo.klae.connex.backend.controllers.RecordCreationPresetController",
        "ooo.klae.connex.backend.controllers.RecordCreationTemplateController",
        "ooo.klae.connex.backend.controllers.TaskController");
    private static final Pattern EXPLICIT_TENANT_TARGET = Pattern.compile(
        "(?:^|/)(?:org|orgs|organization|organizations|workspace|workspaces)/\\{[^/]+}",
        Pattern.CASE_INSENSITIVE);
    private static final List<Class<?>> ASSISTANT_CONTROLLERS = List.of(
        ooo.klae.connex.backend.controllers.AiAssistantController.class,
        ooo.klae.connex.backend.controllers.AiAssistantProactiveController.class,
        ooo.klae.connex.backend.controllers.AiAssistantSkillController.class);
    private static final List<String> CLIENT_DRIVEN_HANDLERS = List.of(
        "ooo.klae.connex.backend.controllers.AiAssistantController#get",
        "ooo.klae.connex.backend.controllers.AiAssistantController#getToolCall",
        "ooo.klae.connex.backend.controllers.AiAssistantController#getTurn",
        "ooo.klae.connex.backend.controllers.AiAssistantController#invitations",
        "ooo.klae.connex.backend.controllers.AiAssistantController#listAttachments",
        "ooo.klae.connex.backend.controllers.AiAssistantController#listToolCalls",
        "ooo.klae.connex.backend.controllers.AiAssistantController#page",
        "ooo.klae.connex.backend.controllers.AiAssistantController#participants",
        "ooo.klae.connex.backend.controllers.AiAssistantController#presence",
        "ooo.klae.connex.backend.controllers.AiAssistantController#previewScope",
        "ooo.klae.connex.backend.controllers.AiAssistantController#touchPresence",
        "ooo.klae.connex.backend.controllers.AiAssistantProactiveController#briefSchedule",
        "ooo.klae.connex.backend.controllers.AiAssistantProactiveController#commandCenter",
        "ooo.klae.connex.backend.controllers.AiAssistantProactiveController#watches",
        "ooo.klae.connex.backend.controllers.AiAssistantSkillController#list");
    private static final String FULLY_SILENT_HANDLER =
        "ooo.klae.connex.backend.controllers.AiAssistantController#touchPresence";

    @Test
    void onlyReviewedCurrentTenantHandlersAreJournalAttributable() {
        assertAttributionSurface(scanControllers(BACKEND_PACKAGE), ATTRIBUTABLE_CONTROLLERS);
        assertTrue(AnnotatedElementUtils.hasAnnotation(
            ooo.klae.connex.backend.controllers.GuidedRecordCreationController.class,
            TenantJournalAttributable.class));
        assertTrue(AnnotatedElementUtils.hasAnnotation(
            ooo.klae.connex.backend.controllers.SequenceController.class,
            TenantJournalAttributable.class));
    }

    @Test
    void onlyReviewedHandlersAreClientDriven() {
        List<String> actual = new ArrayList<>();
        List<String> fullySilent = new ArrayList<>();
        List<String> unjournaled = new ArrayList<>();
        for (Class<?> controller : scanControllers(BACKEND_PACKAGE)) {
            boolean classAttributable =
                AnnotatedElementUtils.hasAnnotation(controller, TenantJournalAttributable.class);
            for (Method method : controller.getDeclaredMethods()) {
                TenantJournalClientDriven marker =
                    AnnotatedElementUtils.findMergedAnnotation(method, TenantJournalClientDriven.class);
                if (marker == null) {
                    continue;
                }
                String handler = controller.getName() + "#" + method.getName();
                actual.add(handler);
                if (!marker.retainFailures()) {
                    fullySilent.add(handler);
                }
                if (!classAttributable
                        && !AnnotatedElementUtils.hasAnnotation(method, TenantJournalAttributable.class)) {
                    unjournaled.add(handler);
                }
            }
        }

        actual.sort(String::compareTo);
        assertEquals(CLIENT_DRIVEN_HANDLERS.stream().sorted().toList(), actual,
            "Client-driven journal omission changed without review of its operator-visibility contract");
        assertEquals(List.of(FULLY_SILENT_HANDLER), fullySilent,
            "Only a handler whose client retries indefinitely may omit its failures");
        assertTrue(unjournaled.isEmpty(),
            "A client-driven marker on a handler that is not journal-attributable is dead code: "
                + unjournaled);
    }

    @Test
    void assistantReadsAreClientDriven() {
        List<String> unmarkedReads = new ArrayList<>();
        for (Class<?> controller : ASSISTANT_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping =
                    AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null
                        || !Set.of(mapping.method()).equals(Set.of(RequestMethod.GET))) {
                    continue;
                }
                if (AnnotatedElementUtils.findMergedAnnotation(
                        method, TenantJournalClientDriven.class) == null) {
                    unmarkedReads.add(controller.getName() + "#" + method.getName());
                }
            }
        }

        assertTrue(unmarkedReads.isEmpty(),
            "Every assistant GET is folded into the client's realtime refresh fan-out and needs a "
                + "journaling decision: " + unmarkedReads);
    }

    @Test
    void sameSimpleNameControllerInAnotherPackageIsRejected() {
        String reviewedController = "ooo.klae.connex.backend.controllers.PersonController";
        String fixturePackage = "ooo.klae.connex.samecontrollerfixture";

        AssertionError failure = assertThrows(AssertionError.class, () ->
            assertAttributionSurface(scanControllers(fixturePackage), List.of(reviewedController)));

        assertTrue(failure.toString().contains(reviewedController));
        assertTrue(failure.toString().contains(fixturePackage + ".PersonController"));
    }

    @Test
    void differentlyNamedExplicitTenantTargetIsRejected() {
        String fixturePackage = "ooo.klae.connex.explicittargetfixture";
        String fixtureController = fixturePackage + ".ExplicitTargetController";

        AssertionError failure = assertThrows(AssertionError.class, () ->
            assertAttributionSurface(scanControllers(fixturePackage), List.of(fixtureController)));

        assertTrue(failure.toString().contains("/api/organizations/{id}"));
    }

    private static List<Class<?>> scanControllers(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<BeanDefinition> definitions = scanner.findCandidateComponents(basePackage);
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition definition : definitions) {
            controllers.add(load(definition));
        }
        return controllers;
    }

    private static void assertAttributionSurface(
            List<Class<?>> controllers, List<String> reviewedControllers) {
        List<String> actual = new ArrayList<>();
        List<String> explicitTenantTargets = new ArrayList<>();
        for (Class<?> controller : controllers) {
            boolean classAttributable =
                AnnotatedElementUtils.hasAnnotation(controller, TenantJournalAttributable.class);
            boolean controllerAttributable = classAttributable;
            List<String> controllerPaths = requestPaths(controller);
            if (classAttributable) {
                recordExplicitTargets(controller.getName(), controllerPaths, explicitTenantTargets);
            }
            for (Method method : controller.getDeclaredMethods()) {
                boolean methodAttributable =
                    AnnotatedElementUtils.hasAnnotation(method, TenantJournalAttributable.class);
                if (methodAttributable) {
                    controllerAttributable = true;
                }
                if (classAttributable || methodAttributable) {
                    recordExplicitHandlerTargets(
                        controller.getName() + "." + method.getName(),
                        controllerPaths,
                        requestPaths(method),
                        explicitTenantTargets);
                }
            }
            if (controllerAttributable) {
                actual.add(controller.getName());
            }
        }

        actual.sort(String::compareTo);
        List<String> expected = reviewedControllers.stream().sorted().toList();
        assertEquals(expected, actual,
            "Support-journal attribution changed without review of its tenant-boundary contract");
        assertTrue(explicitTenantTargets.isEmpty(),
            "Journal-attributable handlers must not carry explicit organization/workspace targets: "
                + explicitTenantTargets);
    }

    private static Class<?> load(BeanDefinition definition) {
        String controllerName = definition.getBeanClassName();
        if (controllerName == null) {
            return fail("Could not resolve scanned controller class");
        }
        try {
            return Class.forName(controllerName);
        } catch (ClassNotFoundException exception) {
            return fail("Could not load controller " + controllerName, exception);
        }
    }

    private static List<String> requestPaths(AnnotatedElement element) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        if (mapping == null) {
            return List.of();
        }
        String[] paths = mapping.path();
        if (paths.length == 0) {
            paths = mapping.value();
        }
        if (paths.length == 0) {
            return List.of("");
        }
        return List.of(paths);
    }

    private static void recordExplicitHandlerTargets(
            String handler,
            List<String> controllerPaths,
            List<String> methodPaths,
            List<String> targets) {
        if (methodPaths.isEmpty()) {
            return;
        }
        List<String> basePaths = controllerPaths.isEmpty() ? List.of("") : controllerPaths;
        for (String basePath : basePaths) {
            List<String> combinedPaths = methodPaths.stream()
                .map(methodPath -> combinePaths(basePath, methodPath))
                .toList();
            recordExplicitTargets(handler, combinedPaths, targets);
        }
    }

    private static void recordExplicitTargets(
            String handler, List<String> paths, List<String> targets) {
        paths.stream()
            .filter(path -> EXPLICIT_TENANT_TARGET.matcher(path).find())
            .map(path -> handler + "=" + path)
            .forEach(targets::add);
    }

    private static String combinePaths(String controllerPath, String methodPath) {
        if (controllerPath.isEmpty()) {
            return methodPath;
        }
        if (methodPath.isEmpty()) {
            return controllerPath;
        }
        if (controllerPath.endsWith("/") && methodPath.startsWith("/")) {
            return controllerPath + methodPath.substring(1);
        }
        if (!controllerPath.endsWith("/") && !methodPath.startsWith("/")) {
            return controllerPath + "/" + methodPath;
        }
        return controllerPath + methodPath;
    }
}
