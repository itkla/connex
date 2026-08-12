package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.ProcessingRestrictionRegistry;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

/** Pins the existing attachment plane used by private assistant-session uploads. */
class AiChatAttachmentLifecycleArchTest {
    private static final String ATTACHMENT_MAPPER =
            "ooo.klae.connex.backend.mappers.AttachmentMapper";

    @Test
    void assistantAttachmentsReuseExportAndTeardownContracts() {
        assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.contains("attachment"));
        assertTrue(TenantLifecycleRegistry.declarations().get("attachment").direct());
    }

    @Test
    void attachmentMapperRemainsReachableForRestrictionAndErasureWork() {
        assertTrue(ProcessingRestrictionRegistry.personReaderAllowlist()
                .containsKey(ATTACHMENT_MAPPER));
    }
}
