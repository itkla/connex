package ooo.klae.connex.backend.mappers;

import java.util.List;

import ooo.klae.connex.backend.secrets.StoredSecret;

/**
 * Persistence for encrypted integration secrets. Control-plane mapper: callers
 * pass explicit scope identifiers after their own permission checks.
 */
public interface SecretValueMapper {
    StoredSecret findById(long id);
    List<String> listKeyIds();
    int upsert(StoredSecret secret);
    int delete(long id);
}
