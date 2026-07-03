package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.User;

class EntityReferenceMapperTest extends AbstractMapperTest {

    @Autowired EntityReferenceMapper entityReferenceMapper;

    private EntityReference reference(String sourceType, int sourceId, String type, int refId, String label) {
        EntityReference reference = new EntityReference();
        reference.setWorkspaceId(workspace.getId());
        reference.setSourceType(sourceType);
        reference.setSourceId(sourceId);
        reference.setRefType(type);
        reference.setRefId(refId);
        reference.setLabel(label);
        return reference;
    }

    /**
     * An inserted reference is returned by findBySource with its fields intact.
     */
    @Test
    void insert_then_findBySource_returnsRow() {
        User mentioned = newUser();

        entityReferenceMapper.insert(reference("note", 100, "user", mentioned.getId(), "User M"));

        List<EntityReference> found = entityReferenceMapper.findBySource(workspace.getId(), "note", 100);
        assertEquals(1, found.size());
        assertEquals("note", found.get(0).getSourceType());
        assertEquals(100, found.get(0).getSourceId());
        assertEquals("user", found.get(0).getRefType());
        assertEquals(mentioned.getId(), found.get(0).getRefId());
        assertEquals("User M", found.get(0).getLabel());
    }

    /**
     * deleteBySource clears every reference for that source entity.
     */
    @Test
    void deleteBySource_removesRows() {
        entityReferenceMapper.insert(reference("task", 7, "user", newUser().getId(), "A"));
        entityReferenceMapper.insert(reference("task", 7, "user", newUser().getId(), "B"));

        entityReferenceMapper.deleteBySource(workspace.getId(), "task", 7);

        assertTrue(entityReferenceMapper.findBySource(workspace.getId(), "task", 7).isEmpty());
    }

    /**
     * A note and a task sharing the same numeric source id do not collide —
     * source_type is part of the key.
     */
    @Test
    void findBySource_discriminatesBySourceType() {
        entityReferenceMapper.insert(reference("note", 1, "user", newUser().getId(), "N"));
        entityReferenceMapper.insert(reference("task", 1, "user", newUser().getId(), "T"));

        List<EntityReference> noteRefs = entityReferenceMapper.findBySource(workspace.getId(), "note", 1);
        List<EntityReference> taskRefs = entityReferenceMapper.findBySource(workspace.getId(), "task", 1);
        assertEquals(1, noteRefs.size());
        assertEquals("N", noteRefs.get(0).getLabel());
        assertEquals(1, taskRefs.size());
        assertEquals("T", taskRefs.get(0).getLabel());
        assertTrue(entityReferenceMapper.findBySource(workspace.getId(), "note", 2).isEmpty());
    }

    /**
     * findBySources batches references across several source ids of one type.
     */
    @Test
    void findBySources_batchesAcrossSourceIds() {
        entityReferenceMapper.insert(reference("task", 10, "user", newUser().getId(), "A"));
        entityReferenceMapper.insert(reference("task", 11, "user", newUser().getId(), "B"));
        entityReferenceMapper.insert(reference("task", 12, "user", newUser().getId(), "C"));

        List<EntityReference> found = entityReferenceMapper.findBySources(workspace.getId(), "task", List.of(10, 12));
        assertEquals(2, found.size());
        assertTrue(found.stream().anyMatch(r -> r.getSourceId() == 10));
        assertTrue(found.stream().anyMatch(r -> r.getSourceId() == 12));
        assertTrue(found.stream().noneMatch(r -> r.getSourceId() == 11));
    }
}
