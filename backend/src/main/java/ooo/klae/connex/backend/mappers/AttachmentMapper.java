package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.beans.Attachment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Mapper interface for {@code Attachment} persistence.
 */

public interface AttachmentMapper {
    List<Attachment> getByEntity(@Param("entityType") String entityType, @Param("entityId") int entityId);
    Attachment getById(int id);
    int insert(Attachment attachment);
    int delete(int id);
    int deleteByEntity(@Param("entityType") String entityType, @Param("entityId") int entityId);
}