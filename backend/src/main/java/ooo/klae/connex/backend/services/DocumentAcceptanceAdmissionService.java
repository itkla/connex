package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.signature.DocumentAcceptanceToken;

/** Performs the uniform hash and catalog lookup for a public document bearer. */
@Service
@RequiredArgsConstructor
public class DocumentAcceptanceAdmissionService {
    private final WorkspaceMapper workspaceMapper;

    /** Resolves one real or canonicalized bearer through the same catalog lookup path. */
    public Admission lookup(String token) {
        boolean originalShapeValid = DocumentAcceptanceToken.hasValidShape(token);
        String tokenHash = DocumentAcceptanceToken.hashForAdmission(token);
        int workspaceId = DocumentAcceptanceToken.workspaceIdForAdmission(token);
        Workspace workspace = workspaceMapper.getActiveById(workspaceId);
        return new Admission(originalShapeValid, workspace, tokenHash);
    }

    /** Result of the public bearer admission lookup. */
    public record Admission(
            boolean originalShapeValid,
            Workspace workspace,
            String tokenHash) {
    }
}
