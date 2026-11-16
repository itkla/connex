package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Keeps the AI security contract's active release floor aligned with deployment authority. */
class ExportPermissionDocumentationArchTest {
    @Test
    void aiSecurityNamesTheDeclaredReleaseFloorAndItsExportCutover() throws IOException {
        String floor = Files.readString(Path.of("..", "deploy", "staging", "rollback-floor"));
        Matcher declared = Pattern.compile("control/V(\\d+)__").matcher(floor);
        assertTrue(declared.find(), "Deployment must declare its control migration rollback floor");
        String document = Files.readString(Path.of("..", "docs", "backend", "AI_SECURITY.md"));
        Matcher documented = Pattern.compile("control V(\\d+) is\\s+the active release rollback floor")
            .matcher(document);
        assertTrue(documented.find(), "AI security must identify the active release-wide floor");
        assertEquals(declared.group(1), documented.group(1));
        assertTrue(document.contains("../UPGRADING.md#export-data-permission-control-v248"));
    }
}
