"use client";

import { useEffect, useState } from "react";

import type { RecordSelectOption } from "@/app/components/records/RecordSelect";
import { search } from "@/app/lib/api";

/** Searches only visible records of the workflow's primary type and cancels obsolete requests. */
export function useWorkflowRecordSearch(recordType: string | null, requestInit: RequestInit, enabled: boolean) {
    const [query, setQuery] = useState("");
    const [result, setResult] = useState<{
        query: string;
        recordType: string | null;
        requestInit: RequestInit;
        records: RecordSelectOption[];
        failed: boolean;
    } | null>(null);

    useEffect(() => {
        if (!enabled || !recordType || query.trim().length < 2) return;
        const controller = new AbortController();
        const timer = setTimeout(() => {
            void search(query.trim(), { ...requestInit, signal: controller.signal })
                .then((results) => {
                    if (controller.signal.aborted) return;
                    const records: RecordSelectOption[] = recordType === "company"
                        ? results.companies.map((company) => ({ id: company.id, label: company.name, imageUrl: company.logoUrl }))
                        : recordType === "person"
                            ? results.people.map((person) => ({ id: person.id, label: person.name, imageUrl: person.imageUrl }))
                            : recordType === "deal"
                                ? results.deals.map((deal) => ({ id: deal.id, label: deal.name }))
                                : [];
                    setResult({ query, recordType, requestInit, records: records.slice(0, 50), failed: false });
                })
                .catch(() => {
                    if (!controller.signal.aborted) setResult({ query, recordType, requestInit, records: [], failed: true });
                });
        }, 200);
        return () => {
            clearTimeout(timer);
            controller.abort();
        };
    }, [enabled, query, recordType, requestInit]);

    const current = result?.query === query && result.recordType === recordType && result.requestInit === requestInit;
    return {
        searchRecords: setQuery,
        records: enabled && current ? result.records : [],
        failed: enabled && current ? result.failed : false,
    };
}
