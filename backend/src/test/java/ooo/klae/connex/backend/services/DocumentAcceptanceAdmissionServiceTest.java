package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.signature.DocumentAcceptanceToken;

@ExtendWith(MockitoExtension.class)
class DocumentAcceptanceAdmissionServiceTest {
    private static final String MALFORMED = "not-a-token";
    private static final String OVERSIZED_WORKSPACE = "w999999999999-" + "b".repeat(64);
    private static final String UNKNOWN = "w0-" + "a".repeat(64);

    @Mock WorkspaceMapper workspaceMapper;
    @InjectMocks DocumentAcceptanceAdmissionService admissionService;

    @Test
    void malformedAndWellShapedUnknownTokensPerformTheSameCatalogLookup() {
        DocumentAcceptanceAdmissionService.Admission malformed = admissionService.lookup(MALFORMED);
        DocumentAcceptanceAdmissionService.Admission oversized =
            admissionService.lookup(OVERSIZED_WORKSPACE);
        DocumentAcceptanceAdmissionService.Admission unknown = admissionService.lookup(UNKNOWN);

        assertFalse(malformed.originalShapeValid());
        assertFalse(oversized.originalShapeValid());
        assertTrue(unknown.originalShapeValid());
        assertNull(malformed.workspace());
        assertNull(oversized.workspace());
        assertNull(unknown.workspace());
        assertEquals(
            DocumentAcceptanceToken.hashForAdmission(MALFORMED),
            malformed.tokenHash());
        assertEquals(malformed.tokenHash(), oversized.tokenHash());
        assertEquals(DocumentAcceptanceToken.hash(UNKNOWN), unknown.tokenHash());
        assertFalse(DocumentAcceptanceToken.hasValidShape(
            DocumentAcceptanceToken.canonicalizeForAdmission(MALFORMED)));
        assertEquals(-1, DocumentAcceptanceToken.workspaceIdForAdmission(MALFORMED));
        verify(workspaceMapper, times(2)).getActiveById(-1);
        verify(workspaceMapper).getActiveById(0);
    }
}
