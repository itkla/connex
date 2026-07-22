package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.exceptions.BadRequestException;
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
    private final ObjectMapper objectMapper;

    private static final Set<String> AUDIT_FIELDS = Set.of(
        "name", "type", "locale", "title", "intro", "terms", "footer", "active");
    private static final int MAX_BODY_DEPTH = 50;
    private static final int MAX_BODY_NODES = 5000;

    public List<DocumentTemplate> getAll() {
        return templateMapper.getAll(workspaceService.getCurrentWorkspaceId());
    }

    public DocumentTemplate getById(int id) {
        return require(workspaceService.getCurrentWorkspaceId(), id);
    }

    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public DocumentTemplate create(DocumentTemplate template) {
        validateBody(template.getBody());
        template.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        templateMapper.insert(template);
        DocumentTemplate saved = require(template.getWorkspaceId(), template.getId());
        auditService.record("document_template.create", "document_template", saved.getId(), saved.getName(),
            "Created document template " + saved.getName(), auditService.diff(null, saved, AUDIT_FIELDS));
        return saved;
    }

    @RequirePermission(Permission.DOCUMENT_MANAGE)
    public DocumentTemplate update(int id, DocumentTemplate template) {
        validateBody(template.getBody());
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

    private void validateBody(String body) {
        if (body == null || body.isBlank()) return;
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException invalid) {
            throw new BadRequestException("Document template body must be valid document content");
        }
        JsonNode type = root.get("type");
        if (!root.isObject() || type == null || !type.isString() || !"doc".equals(type.asString())) {
            throw new BadRequestException("Document template body must be a document");
        }
        checkStructure(root, 0, new int[] { 0 });
    }

    /**
     * Rejects a body that would be un-generatable server-side: the depth cap mirrors the resolver's,
     * so any body that saves is guaranteed to resolve, and the node budget bounds resolution cost.
     */
    private void checkStructure(JsonNode node, int depth, int[] count) {
        if (depth > MAX_BODY_DEPTH) {
            throw new BadRequestException("Document template body is nested too deeply");
        }
        if (++count[0] > MAX_BODY_NODES) {
            throw new BadRequestException("Document template body is too large");
        }
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode child : content) {
                if (child.isObject()) {
                    checkStructure(child, depth + 1, count);
                }
            }
        }
    }
}
