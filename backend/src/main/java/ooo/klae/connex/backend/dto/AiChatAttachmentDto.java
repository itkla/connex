package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.Attachment;

/**
 * Private assistant-session attachment metadata.
 * @param id attachment id
 * @param fileName safe display name
 * @param contentType canonical media type
 * @param size stored byte size
 * @param kind text or image input kind
 * @param createdAt creation timestamp
 */
public record AiChatAttachmentDto(
        int id,
        String fileName,
        String contentType,
        long size,
        String kind,
        String createdAt) {

    /** Maps one persisted assistant attachment to its private wire shape. */
    public static AiChatAttachmentDto from(Attachment attachment) {
        return new AiChatAttachmentDto(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSize() == null ? 0 : attachment.getSize(),
                attachment.getContentType().startsWith("image/") ? "image" : "text",
                attachment.getCreatedAt());
    }
}
