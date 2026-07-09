package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.secrets.StoredSecret;

/**
 * Persistence for encrypted integration secrets. Control-plane mapper: callers
 * pass explicit scope identifiers after their own permission checks.
 */
public interface SecretValueMapper {
    StoredSecret findById(long id);
    List<String> listKeyIds();
    List<StoredSecret> listForDiagnostics(@Param("scopeType") String scopeType, @Param("scopeId") Integer scopeId);
    List<StoredSecret> listRewrapCandidates(@Param("activeKeyId") String activeKeyId, @Param("limit") int limit);
    int upsert(StoredSecret secret);
    int updateRewrapped(@Param("secret") StoredSecret secret, @Param("previousKeyId") String previousKeyId,
            @Param("previousEncryptedDataKey") String previousEncryptedDataKey,
            @Param("previousCiphertext") String previousCiphertext);
    int delete(long id);
}
