package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DocumentTemplateMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Manages workspace-scoped commercial-document templates. Reads are workspace-scoped; mutations
 * require {@link Permission#DOCUMENT_MANAGE}.
 */
@Service
@RequiredArgsConstructor
public class DocumentTemplateService {
    private final DocumentTemplateMapper templateMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    private static final Set<String> AUDIT_FIELDS = Set.of(
        "name", "type", "locale", "title", "intro", "terms", "footer", "active");

    public List<DocumentTemplate> getAll() {
        return templateMapper.getAll(workspaceService.getCurrentWorkspaceId());
    }

    public DocumentTemplate getById(int id) {
        return require(workspaceService.getCurrentWorkspaceId(), id);
    }

    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public DocumentTemplate create(DocumentTemplate template) {
        template.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        templateMapper.insert(template);
        DocumentTemplate saved = require(template.getWorkspaceId(), template.getId());
        auditService.record("document_template.create", "document_template", saved.getId(), saved.getName(),
            "Created document template " + saved.getName(), auditService.diff(null, saved, AUDIT_FIELDS));
        return saved;
    }

    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public DocumentTemplate update(int id, DocumentTemplate template) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        DocumentTemplate before = require(workspaceId, id);
        template.setId(id);
        template.setWorkspaceId(workspaceId);
        templateMapper.update(template);
        DocumentTemplate after = require(workspaceId, id);
        auditService.record("document_template.update", "document_template", id, after.getName(),
            "Updated document template " + after.getName(), auditService.diff(before, after, AUDIT_FIELDS));
        return after;
    }

    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        DocumentTemplate before = require(workspaceId, id);
        templateMapper.delete(workspaceId, id);
        auditService.record("document_template.delete", "document_template", id, before.getName(),
            "Deleted document template " + before.getName(), auditService.diff(before, null, AUDIT_FIELDS));
    }

    DocumentTemplate require(int workspaceId, int id) {
        DocumentTemplate template = templateMapper.getById(workspaceId, id);
        if (template == null) throw new ResourceNotFoundException("Document template not found with id: " + id);
        return template;
    }
}
