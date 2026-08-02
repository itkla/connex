/**
 * Report measures whose figure basis diverges from what the widget title alone implies, mapped to
 * the message key describing that basis. Mirrors the server-side `FigureReconciliationRegistry`
 * declarations: only measures with a declared divergence-prone basis carry a line, so the caption
 * stays meaningful instead of decorating every widget.
 */
const BASIS_KEY_BY_MEASURE: Readonly<Record<string, string>> = {
    won_revenue: 'document.figureBasis.wonRevenue',
    open_pipeline_value: 'document.figureBasis.openPipeline',
    forecast_weighted: 'document.figureBasis.forecastWeighted',
};

/**
 * Returns the basis message key for a report measure, or `null` when the measure has no declared
 * divergence and therefore needs no basis line.
 */
export function reportFigureBasisKey(measure: string | null | undefined): string | null {
    if (!measure) return null;
    return BASIS_KEY_BY_MEASURE[measure] ?? null;
}
