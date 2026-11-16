export type ServerPickerSearch = {
    query: string;
    pending: boolean;
    accepts: (item: unknown) => boolean;
};

type SearchItem = { id: string | number };

/** Uses the same query identity for requests and the picker's synchronous selection guard. */
export function pickerQueryKey(query: string): string {
    return query.normalize('NFKC').trim();
}

/** Binds selectable record IDs to the query that produced them, independently of retained labels. */
export function createServerPickerSearch(
    query: string,
    result: { query: string; items: readonly SearchItem[] } | null,
): ServerPickerSearch {
    const key = pickerQueryKey(query);
    const pending = result === null || pickerQueryKey(result.query) !== key;
    const ids = new Set(pending ? [] : result.items.map((item) => String(item.id)));
    return {
        query: key,
        pending,
        accepts: (item) => !pending && item !== null && typeof item === 'object'
            && 'id' in item && (typeof item.id === 'number' || typeof item.id === 'string')
            && ids.has(String(item.id)),
    };
}
