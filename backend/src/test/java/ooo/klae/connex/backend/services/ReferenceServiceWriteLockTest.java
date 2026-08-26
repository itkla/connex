package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.EntityReferenceMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

@ExtendWith(MockitoExtension.class)
class ReferenceServiceWriteLockTest {

    @Mock EntityReferenceMapper entityReferenceMapper;
    @Mock WorkspaceService workspaceService;
    @Mock PersonMapper personMapper;
    @Mock DealMapper dealMapper;
    @Mock CompanyMapper companyMapper;
    @Mock NoteMapper noteMapper;
    @Mock AttachmentMapper attachmentMapper;
    @Mock TaskMapper taskMapper;
    @Mock ActivityMapper activityMapper;
    @Mock WorkspaceMapper workspaceMapper;
    @InjectMocks ReferenceService referenceService;
    @Captor ArgumentCaptor<EntityReference> referenceCaptor;

    /** A first reference insert avoids the empty-range delete that can deadlock concurrent creates. */
    @Test
    void firstReferenceSyncSkipsEmptyRangeDelete() {
        when(entityReferenceMapper.findBySource(9, ReferenceService.SOURCE_NOTE, 21))
            .thenReturn(List.of());
        when(workspaceService.getCurrentUserId()).thenReturn(5);
        when(companyMapper.exists(9, 14)).thenReturn(true);

        referenceService.syncReferences(9, ReferenceService.SOURCE_NOTE, 21, "[Acme](company:14)");

        verify(entityReferenceMapper, never()).deleteBySource(9, ReferenceService.SOURCE_NOTE, 21);
        verify(entityReferenceMapper).insert(referenceCaptor.capture());
        EntityReference stored = referenceCaptor.getValue();
        assertEquals(9, stored.getWorkspaceId());
        assertEquals(21, stored.getSourceId());
        assertEquals("company", stored.getRefType());
        assertEquals(14, stored.getRefId());
    }
}
