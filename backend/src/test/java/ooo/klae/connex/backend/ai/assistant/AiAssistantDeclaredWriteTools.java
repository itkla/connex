package ooo.klae.connex.backend.ai.assistant;

import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Every production {@link AiAssistantWriteTool} bean on the classpath, for checks that must hold
 * for each declared tool without a test naming them.
 *
 * <p>Each tool is built over one Mockito mock per constructor dependency, so a check can prove which
 * of them a method touched. A tool added later is picked up here without editing any test.
 */
final class AiAssistantDeclaredWriteTools {

    private AiAssistantDeclaredWriteTools() {
    }

    /** A discovered tool and the mocks it was constructed over. */
    record Discovered(AiAssistantWriteTool tool, List<Object> dependencies) {
    }

    /** @return every production write-tool bean, in name order */
    static List<Discovered> discover() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AiAssistantWriteTool.class));
        List<Discovered> tools = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(
                AiAssistantWriteTool.class.getPackageName())) {
            Class<?> type = load(candidate.getBeanClassName());
            if (type.isAnnotationPresent(Component.class)) {
                tools.add(construct(type));
            }
        }
        tools.sort(Comparator.comparing(discovered -> discovered.tool().name()));
        return List.copyOf(tools);
    }

    /** @return the discovered tools alone, in name order */
    static List<AiAssistantWriteTool> tools() {
        return discover().stream().map(Discovered::tool).toList();
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("A scanned write tool is not loadable", exception);
        }
    }

    private static Discovered construct(Class<?> type) {
        Constructor<?>[] constructors = type.getConstructors();
        if (constructors.length != 1) {
            throw new IllegalStateException(type + " must declare one injection constructor");
        }
        List<Object> dependencies = new ArrayList<>();
        for (Class<?> parameter : constructors[0].getParameterTypes()) {
            dependencies.add(mock(parameter));
        }
        try {
            Object tool = constructors[0].newInstance(dependencies.toArray());
            if (!(tool instanceof AiAssistantWriteTool writeTool)) {
                throw new IllegalStateException(type + " is not an assistant write tool");
            }
            return new Discovered(writeTool, List.copyOf(dependencies));
        } catch (InstantiationException | IllegalAccessException
                | InvocationTargetException exception) {
            throw new IllegalStateException(type + " could not be constructed", exception);
        }
    }
}
