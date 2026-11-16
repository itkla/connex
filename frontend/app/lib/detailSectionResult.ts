/** Safe, serializable failure metadata for one server-loaded detail section. */
export type DetailSectionFailure = {
    kind: 'failed';
    status?: number;
    reference?: string;
};

/** A failed request never supplies data that could be mistaken for a successful empty result. */
export type DetailSectionResult<T> = { kind: 'loaded'; data: T } | DetailSectionFailure;

/** Selects a dependent section's failure, prioritizing a permission denial over an outage. */
export function detailSectionFailure(...results: DetailSectionResult<unknown>[]): DetailSectionFailure | null {
    return results.find((result): result is DetailSectionFailure => result.kind === 'failed' && result.status === 403)
        ?? results.find((result): result is DetailSectionFailure => result.kind === 'failed')
        ?? null;
}
