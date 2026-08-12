package ooo.klae.connex.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.RecordComment;

class RecordCommentDtoTest {

    /**
     * The chip renderer resolves references by {@code type}/{@code id}, so the
     * wire shape must be {@link ReferenceDto}, never the raw
     * {@link EntityReference} bean whose {@code refType}/{@code refId} fields
     * silently break every mention chip.
     */
    @Test
    void referencesSerializeAsChipResolvableReferenceDtos() {
        EntityReference reference = new EntityReference();
        reference.setWorkspaceId(7);
        reference.setSourceType("comment");
        reference.setSourceId(31);
        reference.setRefType("user");
        reference.setRefId(42);
        reference.setLabel("Mirei Takahara");

        RecordComment comment = new RecordComment();
        comment.setId(31L);
        comment.setThreadId(11L);
        comment.setAuthorUserId(5);
        comment.setAuthorDisplayName("Kenji Ozu");
        comment.setContent("[Mirei Takahara](user:42)");
        comment.setReferences(List.of(reference));

        RecordCommentDto dto = RecordCommentDto.from(comment);

        assertEquals(1, dto.references().size());
        assertEquals("user", dto.references().getFirst().getType());
        assertEquals(42, dto.references().getFirst().getId());
        assertEquals("Mirei Takahara", dto.references().getFirst().getLabel());
    }
}
