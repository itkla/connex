package ooo.klae.connex.backend.storage;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.businesscard.BusinessCardBinaryStore;

/**
 * Business-card storage adapter backed by the managed private object store.
 */
@Component
@RequiredArgsConstructor
public class ManagedBusinessCardBinaryStore implements BusinessCardBinaryStore {
    private final ManagedObjectService managedObjectService;

    @Override
    public boolean isReady() {
        return managedObjectService.isReady();
    }

    @Override
    public StoredBusinessCard store(
            int workspaceId,
            String fileName,
            String contentType,
            byte[] content) {
        ManagedObjectService.StoredBinary stored = managedObjectService.storeAttachment(
            workspaceId, fileName, contentType, content);
        managedObjectService.compensateAttachmentOnRollback(workspaceId, stored.url());
        return new StoredBusinessCard(stored.url(), stored.size());
    }

    @Override
    public void delete(int workspaceId, String url) {
        managedObjectService.deleteAttachment(workspaceId, url);
    }
}
