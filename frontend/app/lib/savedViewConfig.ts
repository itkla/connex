import {
    evaluableSegmentDefinition,
    hasSegmentConditions,
    normalizeSegmentDefinition,
} from "@/app/lib/segmentDefinition";
import type { SavedViewConfig } from "@/app/lib/types";

/** Canonical string for comparing the filter, query, sort, and segment scope of saved views. */
export function savedViewConfigKey(config: SavedViewConfig | null | undefined): string {
    const filters = config?.filters ?? {};
    const sorted: Record<string, string[]> = {};
    for (const key of Object.keys(filters).sort()) {
        const values = filters[key];
        if (values && values.length > 0) sorted[key] = [...values].sort();
    }
    const normalizedSegments = normalizeSegmentDefinition(config?.segments);
    const evaluableSegments = normalizedSegments ? evaluableSegmentDefinition(normalizedSegments) : null;
    const segments = evaluableSegments && hasSegmentConditions(evaluableSegments)
        ? JSON.stringify(evaluableSegments)
        : "";
    return JSON.stringify({
        filters: sorted,
        query: (config?.query ?? "").trim(),
        sortKey: config?.sortKey ?? null,
        sortDirection: config?.sortDirection ?? "asc",
        segments,
    });
}
