package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

/**
 * One visible target account's workspace-owned open pipeline in a single currency.
 * @param companyId visible target company id
 * @param companyName visible target company name
 * @param currency deal currency
 * @param accountValue non-negative open pipeline value
 */
public record ReportNetworkAccountRow(
        int companyId,
        String companyName,
        String currency,
        BigDecimal accountValue) {
}
