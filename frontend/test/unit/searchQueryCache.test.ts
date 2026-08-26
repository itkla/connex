import { describe, expect, it } from "vitest";

import {
    entriesForWorkspace,
    isFresh,
    nearestPrefixResults,
    rememberSearch,
    SEARCH_CACHE_LIMIT,
    SEARCH_CACHE_TTL_MS,
    type SearchCache,
} from "@/app/lib/search/queryCache";
import type { SearchResults } from "@/app/lib/types";

const MIN_QUERY_LENGTH = 2;

function results(): SearchResults {
    return {
        users: [], companies: [], people: [], deals: [], pipelines: [], tags: [], activities: [],
        notes: [], tasks: [], attachments: [], products: [], campaigns: [], reports: [],
        documentTemplates: [], documents: [], workflows: [],
    };
}

function cacheOf(entries: Array<[string, number]>): SearchCache {
    let cache: SearchCache = new Map();
    for (const [query, at] of entries) cache = rememberSearch(cache, query, results(), at);
    return cache;
}

describe("global search query cache", () => {
    it("serves a repeated query from cache instead of the network", () => {
        const cache = cacheOf([["acme", 1_000]]);
        const cached = cache.get("acme");

        expect(cached).toBeDefined();
        expect(isFresh(cached!, 1_000 + SEARCH_CACHE_TTL_MS)).toBe(true);
    });

    it("revalidates an entry once it ages past the freshness window", () => {
        const cache = cacheOf([["acme", 1_000]]);

        expect(isFresh(cache.get("acme")!, 1_000 + SEARCH_CACHE_TTL_MS + 1)).toBe(false);
    });

    it("re-stamps a refetched query rather than keeping the stale timestamp", () => {
        let cache = cacheOf([["acme", 1_000]]);
        cache = rememberSearch(cache, "acme", results(), 90_000);

        expect(cache.size).toBe(1);
        expect(isFresh(cache.get("acme")!, 90_000)).toBe(true);
    });

    it("evicts the least recently used query once the cache is full", () => {
        const entries: Array<[string, number]> = Array.from(
            { length: SEARCH_CACHE_LIMIT + 1 },
            (_, index) => [`query-${index}`, index],
        );

        const cache = cacheOf(entries);

        expect(cache.size).toBe(SEARCH_CACHE_LIMIT);
        expect(cache.has("query-0")).toBe(false);
        expect(cache.has(`query-${SEARCH_CACHE_LIMIT}`)).toBe(true);
    });

    it("keeps a re-served query out of the eviction line", () => {
        let cache = cacheOf(
            Array.from({ length: SEARCH_CACHE_LIMIT }, (_, index) => [`query-${index}`, index]),
        );
        cache = rememberSearch(cache, "query-0", results(), 500);
        cache = rememberSearch(cache, "newest", results(), 600);

        expect(cache.has("query-0")).toBe(true);
        expect(cache.has("query-1")).toBe(false);
    });

    it("falls back to the longest already-searched prefix while a longer query resolves", () => {
        const cache = cacheOf([["ac", 1], ["acme", 2]]);

        expect(nearestPrefixResults(cache, "acme corp", MIN_QUERY_LENGTH)).toBe(cache.get("acme")!.data);
    });

    it("matches a prefix that ends on the space the user just typed", () => {
        const cache = cacheOf([["acme", 1]]);

        expect(nearestPrefixResults(cache, "acme ", MIN_QUERY_LENGTH)).toBe(cache.get("acme")!.data);
    });

    it("holds nothing rather than unrelated rows when no prefix was searched", () => {
        const cache = cacheOf([["zeta", 1]]);

        expect(nearestPrefixResults(cache, "acme", MIN_QUERY_LENGTH)).toBeNull();
    });

    it("never falls back to a prefix shorter than the search floor", () => {
        const cache = cacheOf([["a", 1]]);

        expect(nearestPrefixResults(cache, "ac", MIN_QUERY_LENGTH)).toBeNull();
    });

    it("serves nothing from a workspace the cache was not filled in", () => {
        const scoped = { workspaceId: 7, entries: cacheOf([["acme", 1]]) };

        expect(entriesForWorkspace(scoped, 7).has("acme")).toBe(true);
        expect(entriesForWorkspace(scoped, 8).size).toBe(0);
        expect(entriesForWorkspace(scoped, null).size).toBe(0);
    });

    it("keeps a workspace's results out of the prefix fallback too", () => {
        const scoped = { workspaceId: 7, entries: cacheOf([["acme", 1]]) };

        expect(nearestPrefixResults(entriesForWorkspace(scoped, 8), "acme corp", MIN_QUERY_LENGTH)).toBeNull();
    });
});
