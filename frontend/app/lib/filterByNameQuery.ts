/** Returns items whose name contains the query (case-insensitive); empty query returns all. */
export function filterByNameQuery<T extends { name: string }>(items: T[], query: string): T[] {
    const needle = query.trim().toLowerCase();
    if (!needle) return items;
    return items.filter((item) => item.name.toLowerCase().includes(needle));
}
