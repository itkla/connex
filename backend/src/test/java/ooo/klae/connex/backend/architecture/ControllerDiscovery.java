package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** Discovers controller classes even when their feature conditions disable registration. */
final class ControllerDiscovery {
    private ControllerDiscovery() {
    }

    static List<Class<?>> scanAllControllers(
            String basePackage, Class<? extends Annotation> controllerAnnotation) {
        AnnotationTypeFilter filter = new AnnotationTypeFilter(controllerAnnotation);
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false) {
                @Override
                protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
                    return filter.match(metadataReader, getMetadataReaderFactory());
                }
            };
        scanner.addIncludeFilter(filter);
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
            String controllerName = definition.getBeanClassName();
            if (controllerName == null) {
                fail("Could not resolve scanned controller class");
            }
            try {
                controllers.add(Class.forName(controllerName));
            } catch (ClassNotFoundException exception) {
                fail("Could not load controller " + controllerName, exception);
            }
        }
        return controllers;
    }
}
