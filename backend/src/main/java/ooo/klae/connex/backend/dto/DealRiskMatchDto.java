package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;

/** A bounded risk match with the current database collation key of its requested text sort. */
@Data
@NoArgsConstructor
public class DealRiskMatchDto {
    private Deal deal;
    private byte[] sortKey;
}
