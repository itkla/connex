package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import ooo.klae.connex.backend.mappers.PersonEdgeMapper;

/** Keeps control-derived organization visibility mandatory for PersonEdge reads. */
class PersonEdgeReadBoundaryArchTest {
    private static final String APPLICATION_PACKAGE = "ooo.klae.connex.backend";
    private static final Set<String> APPROVED_MAPPER_INJECTORS = Set.of(
        "ConnectionService", "IntroductionService", "PersonEdgeReadService");
    private static final String READ_METHODS = String.join("|",
        "getConnections", "getTopConnections", "getAllEdges",
        "getEdgesForNetworkReport", "getEdgesForReverseIntroReport");

    @Test
    void onlyTheVisibilityBoundaryCanCallPersonEdgeReads() throws Exception {
        List<String> injectors = new ArrayList<>();
        List<String> directReadViolations = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(true);

        for (BeanDefinition definition : scanner.findCandidateComponents(APPLICATION_PACKAGE)) {
            Class<?> service = Class.forName(definition.getBeanClassName());
            for (Field field : service.getDeclaredFields()) {
                if (field.getType() != PersonEdgeMapper.class) {
                    continue;
                }
                injectors.add(service.getSimpleName());
                if (service.getSimpleName().equals("PersonEdgeReadService")) {
                    continue;
                }
                String source = sourceFor(service);
                Pattern directRead = Pattern.compile(
                    "\\b" + Pattern.quote(field.getName()) + "\\s*\\.\\s*(?:" + READ_METHODS + ")\\s*\\(");
                if (directRead.matcher(source).find()) {
                    directReadViolations.add(service.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertEquals(APPROVED_MAPPER_INJECTORS, Set.copyOf(injectors),
            "PersonEdgeMapper injection changed; read callers must use PersonEdgeReadService");
        assertTrue(directReadViolations.isEmpty(),
            "PersonEdge read methods require the control-derived visibility boundary: " + directReadViolations);
    }

    private static String sourceFor(Class<?> type) throws IOException {
        Path source = repoRoot().resolve("backend/src/main/java")
            .resolve(type.getName().replace('.', '/') + ".java");
        assertTrue(Files.exists(source), "Service source not found: " + source);
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.exists(current.resolve("backend"))) {
            return current;
        }
        Path parent = current.getParent();
        return parent == null ? current : parent;
    }
}
