package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Tag} CRUD and tag-association reads.
 * Tags are per-workspace; every read/write is scoped to the active workspace.
 * Delegates persistence to {@code TagMapper}.
 */

@Service
@RequiredArgsConstructor
public class TagService {
    private final TagMapper tagMapper;
    private final DealMapper dealMapper;
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final ReferenceService referenceService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("name", "color");

    /**
     * Retrieves all {@code Tag} records in the active workspace.
     */
    public List<Tag> getAllTags() {
        return tagMapper.getAllTags(workspaceService.getCurrentWorkspaceId());
    }

    /**
     * Retrieves a workspace-scoped {@code Tag} by ID, throwing if absent.
     */
    public Tag getTagById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Tag tag = tagMapper.getTagById(workspaceId, id);
        if (tag == null) throw new ResourceNotFoundException("Tag not found with id: " + id);
        tag.setDeals(dealMapper.getDealsByTagId(workspaceId, id).toArray(Deal[]::new));
        return tag;
    }

    /**
     * Creates a new {@code Tag} in the active workspace. The ID is auto-generated.
     */
    @RequirePermission(Permission.TAG_MANAGE)
    public Tag create(Tag tag) {
        tag.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        tagMapper.insert(tag);
        auditService.record("tag.create", "tag", tag.getId(), tag.getName(),
            "Created tag " + tag.getName(),
            auditService.diff(null, tag, AUDIT_FIELDS));
        return tag;
    }

    /**
     * Updates an existing {@code Tag} in the active workspace.
     */
    @RequirePermission(Permission.TAG_MANAGE)
    public Tag update(int id, Tag tag) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Tag before = requireTag(workspaceId, id);
        tag.setId(id);
        tag.setWorkspaceId(workspaceId);
        tagMapper.update(tag);
        auditService.record("tag.update", "tag", id, tag.getName(),
            "Updated tag " + tag.getName(),
            auditService.diff(before, tag, AUDIT_FIELDS));
        return tag;
    }

    /**
     * Deletes a {@code Tag} in the active workspace.
     */
    @RequirePermission(Permission.TAG_MANAGE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Tag before = requireTag(workspaceId, id);
        tagMapper.delete(workspaceId, id);
        auditService.record("tag.delete", "tag", id, before.getName(),
            "Deleted tag " + before.getName(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }

    /**
     * Retrieves the tags associated with a person.
     */
    public List<Tag> getTagsByPersonId(int personId) {
        return tagMapper.getTagsByPersonId(workspaceService.getCurrentWorkspaceId(), personId);
    }

    /**
     * Retrieves the tags associated with a company.
     */
    public List<Tag> getTagsByCompanyId(int companyId) {
        return tagMapper.getTagsByCompanyId(workspaceService.getCurrentWorkspaceId(), companyId);
    }

    /**
     * Retrieves the tags associated with a deal in the active workspace.
     */
    public List<Tag> getTagsByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!dealMapper.exists(workspaceId, dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return tagMapper.getTagsByDealId(workspaceService.getCurrentWorkspaceId(), dealId);
    }

    /**
     * Retrieves the deals labelled with a tag, in the active workspace.
     */
    public List<Deal> getDealsByTagId(int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTag(workspaceId, tagId);
        return referenceService.hydrateDeals(workspaceId, dealMapper.getDealsByTagId(workspaceId, tagId));
    }

    /**
     * Retrieves the people labelled with a tag, in the active workspace.
     */
    public List<Person> getPersonsByTagId(int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTag(workspaceId, tagId);
        return personMapper.getPersonsByTagId(workspaceId, tagId);
    }

    /**
     * Retrieves the companies labelled with a tag, in the active workspace.
     */
    public List<Company> getCompaniesByTagId(int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireTag(workspaceId, tagId);
        return companyMapper.getCompaniesByTagId(workspaceId, tagId);
    }

    /**
     * Loads a tag that must exist in the active workspace, else 404.
     */
    private Tag requireTag(int workspaceId, int tagId) {
        Tag tag = tagMapper.getTagById(workspaceId, tagId);
        if (tag == null) throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        return tag;
    }
}
