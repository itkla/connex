/** Returns NFKC-normalized, case-insensitive name matches; a blank query returns all items. */
export function filterByNameQuery<T extends { name: string }>(items: T[], query: string): T[] {
    const needle = query.normalize("NFKC").trim().toLowerCase();
    if (!needle) return items;
    return items.filter((item) => item.name.normalize("NFKC").toLowerCase().includes(needle));
}
