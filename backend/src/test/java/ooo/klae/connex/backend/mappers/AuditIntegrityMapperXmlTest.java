package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Pins exact runtime verification of all permanent audit guards. */
class AuditIntegrityMapperXmlTest {

    @Test
    void appendOnlyReadinessRequiresAllExactTriggerDefinitions() throws Exception {
        String resource = "mappers/AuditIntegrityMapper.xml";
        String xml;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String required : Set.of(
                "COUNT(*) = 3",
                "TRIGGER_SCHEMA = DATABASE()",
                "EVENT_OBJECT_SCHEMA = DATABASE()",
                "EVENT_OBJECT_TABLE = 'audit_log'",
                "'trg_audit_log_no_update'",
                "'trg_audit_log_no_update_v129'",
                "'trg_audit_log_no_delete'",
                "ACTION_TIMING = 'BEFORE'",
                "EVENT_MANIPULATION = 'UPDATE'",
                "EVENT_MANIPULATION = 'DELETE'",
                "ACTION_ORIENTATION = 'ROW'",
                "ACTION_CONDITION IS NULL",
                "ACTION_STATEMENT =")) {
            assertTrue(xml.contains(required), required);
        }
        assertTrue(xml.contains(
            "SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''audit_log is append-only''"));
    }
}
