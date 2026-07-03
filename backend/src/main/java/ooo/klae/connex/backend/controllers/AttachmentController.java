package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.AttachmentDto;
import ooo.klae.connex.backend.dto.AttachmentFacets;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.services.AttachmentService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.util.LikePattern;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Attachment} operations. Attachments are generic:
 * any entity is addressed by {@code entityType} + {@code entityId}.
 * The uploader is taken from the authenticated session, never the request body.
 * Accepts and returns {@code AttachmentDto}. Delegates to {@code AttachmentService}.
 */

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {
    private final AttachmentService attachmentService;
    private final AuthService authService;

    /**
     * GET endpoint to list attachments. With {@code entityType} + {@code entityId}
     * it scopes to a single record; with neither it returns every attachment across
     * all entities (used by the Files library), each carrying its resolved
     * {@code entityLabel} so the client can link back to the owning record.
     */
    @GetMapping
    public List<AttachmentDto> getAttachments(
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) Integer entityId
    ) {
        List<Attachment> attachments = (entityType != null && entityId != null)
            ? attachmentService.getByEntity(entityType, entityId)
            : attachmentService.getAll();
        return attachments.stream().map(AttachmentDto::from).toList();
    }

    /**
     * GET endpoint for a paginated, searchable, filterable slice of every attachment.
     * Powers the Files library. {@code sort} is one of newest|oldest|name|largest;
     * {@code types} filters by owning entity type, {@code kinds} by derived file kind.
     */
    @GetMapping("/page")
    public PageResponse<AttachmentDto> getAttachmentsPage(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "24") int size,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) List<String> types,
        @RequestParam(required = false) List<String> kinds,
        @RequestParam(required = false) List<Integer> tagIds,
        @RequestParam(required = false) Boolean orphaned
    ) {
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 1);
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        int offset = Math.max(0, (page - 1) * size);
        List<AttachmentDto> items = attachmentService.getPage(query, sort, types, kinds, tagIds, orphaned, size, offset)
            .stream().map(AttachmentDto::from).toList();
        return new PageResponse<>(items, attachmentService.countPage(query, types, kinds, tagIds, orphaned));
    }

    /**
     * GET endpoint for the Files library filter facets (counts by source and kind,
     * plus totals), computed across the whole table rather than the current page.
     */
    @GetMapping("/facets")
    public AttachmentFacets getAttachmentFacets() {
        return attachmentService.facets();
    }

    /**
     * GET endpoint to retrieve a single attachment by ID.
     */
    @GetMapping("/{id:\\d+}")
    public AttachmentDto getAttachmentById(@PathVariable int id) {
        return AttachmentDto.from(attachmentService.getById(id));
    }

    /**
     * GET endpoint that resolves an attachment by its blob URL within the caller's
     * workspace (404 when the workspace owns no attachment at that URL). The upload
     * route calls this to authorize a blob deletion before unlinking the file.
     */
    @GetMapping("/by-url")
    public AttachmentDto getAttachmentByUrl(@RequestParam String url) {
        return AttachmentDto.from(attachmentService.getByUrl(url));
    }

    /**
     * POST endpoint to record a new attachment. The binary is uploaded to the
     * Next.js filesystem first; this only persists the resulting URL + metadata.
     */
    @PostMapping
    public AttachmentDto createAttachment(@Valid @RequestBody AttachmentDto dto) {
        Attachment attachment = dto.toBean();
        attachment.setUploadedBy(authService.getCurrentUser());
        return AttachmentDto.from(attachmentService.create(attachment));
    }

    /**
     * DELETE endpoint to remove an attachment record by ID.
     */
    @DeleteMapping("/{id}")
    public void deleteAttachment(@PathVariable int id) {
        attachmentService.delete(id);
    }

    /**
     * GET endpoint to list the tags attached to an attachment.
     */
    @GetMapping("/{id}/tags")
    public List<TagDto> getTagsForAttachment(@PathVariable int id) {
        return attachmentService.getTags(id).stream().map(TagDto::from).toList();
    }

    /**
     * POST endpoint to attach a tag to an attachment.
     */
    @PostMapping("/{id}/tags/{tagId}")
    public void addTagToAttachment(@PathVariable int id, @PathVariable int tagId) {
        attachmentService.addTag(id, tagId);
    }

    /**
     * DELETE endpoint to detach a tag from an attachment.
     */
    @DeleteMapping("/{id}/tags/{tagId}")
    public void removeTagFromAttachment(@PathVariable int id, @PathVariable int tagId) {
        attachmentService.removeTag(id, tagId);
    }

    /**
     * PUT endpoint to replace the full set of tags on an attachment.
     */
    @PutMapping("/{id}/tags")
    public List<TagDto> replaceTagsForAttachment(@PathVariable int id, @RequestBody List<Integer> tagIds) {
        return attachmentService.replaceTags(id, tagIds).stream().map(TagDto::from).toList();
    }
}