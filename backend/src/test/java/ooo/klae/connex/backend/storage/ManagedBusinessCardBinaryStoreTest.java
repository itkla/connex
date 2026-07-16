package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.businesscard.BusinessCardBinaryStore.StoredBusinessCard;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;

@ExtendWith(MockitoExtension.class)
class ManagedBusinessCardBinaryStoreTest {
    @Mock ManagedObjectService managedObjectService;

    @Test
    void returnsTheManagedStoredReference() {
        byte[] bytes = { 1, 2, 3 };
        String url = "/api/attachments/content/550e8400-e29b-41d4-a716-446655440000.jpg";
        when(managedObjectService.storeAttachment(9, "card.jpg", "image/jpeg", bytes))
            .thenReturn(new StoredBinary(url, "card.jpg", "image/jpeg", bytes.length));
        ManagedBusinessCardBinaryStore store = new ManagedBusinessCardBinaryStore(managedObjectService);

        StoredBusinessCard stored = store.store(9, "card.jpg", "image/jpeg", bytes);

        assertEquals(new StoredBusinessCard(url, bytes.length), stored);
    }
}
