package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.AttachmentDto;
import ooo.klae.connex.backend.services.AttachmentService;
import ooo.klae.connex.backend.services.AuthService;

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
     * GET endpoint to list attachments for a given entity.
     */
    @GetMapping
    public List<AttachmentDto> getAttachments(
        @RequestParam String entityType,
        @RequestParam int entityId
    ) {
        return attachmentService.getByEntity(entityType, entityId).stream().map(AttachmentDto::from).toList();
    }

    /**
     * GET endpoint to retrieve a single attachment by ID.
     */
    @GetMapping("/{id}")
    public AttachmentDto getAttachmentById(@PathVariable int id) {
        return AttachmentDto.from(attachmentService.getById(id));
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
}