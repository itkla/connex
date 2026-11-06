package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.validation.CurrencyCodes;

/** Resolves filters against existing workspace buckets before requiring a new ISO monetary code. */
@Service
@RequiredArgsConstructor
public class CurrencyFilterService {
    private final DealMapper dealMapper;
    private final WorkspaceService workspaceService;

    /** Preserves an existing bucket exactly; null is absence and blank never drops the predicate. */
    public String resolve(String value) {
        if (value == null) return null;
        if (value.isBlank()) throw new BadRequestException("currency must not be blank");
        var buckets = dealMapper.countsByCurrency(workspaceService.getCurrentWorkspaceId());
        for (var bucket : buckets) {
            if (value.equals(bucket.getKey())) return bucket.getKey();
        }
        for (var bucket : buckets) {
            if (CurrencyCodes.equivalent(value, bucket.getKey())) return bucket.getKey();
        }
        return CurrencyCodes.required(value);
    }
}
