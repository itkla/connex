package ooo.klae.connex.backend.dto;

/** Company deal totals for one currency, separating realized wins from open forecast. */
public record CompanyRevenueCurrencyDto(
    String currency,
    long dealCount,
    double pastRevenue,
    double projectedRevenue
) {}
