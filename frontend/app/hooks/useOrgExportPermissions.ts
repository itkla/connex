"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { getOrgExportPermissions } from "@/app/lib/api";
import type { ExportPermissions } from "@/app/lib/types";

type PolicyRead = { orgId: number; permissions: ExportPermissions };

/** Resolves organization export grants without using workspace permissions as organization authority. */
export function useOrgExportPermissions(orgId: number | undefined) {
    const [read, setRead] = useState<PolicyRead | null>(null);
    const currentOrg = useRef(orgId);
    useLayoutEffect(() => { currentOrg.current = orgId; }, [orgId]);
    const refresh = useCallback((signal?: AbortSignal) => {
        if (orgId === undefined) return;
        return getOrgExportPermissions(orgId, { signal }).then((permissions) => {
            if (!signal?.aborted && currentOrg.current === orgId) setRead({ orgId, permissions });
        }).catch(() => {
            if (!signal?.aborted && currentOrg.current === orgId) setRead(null);
        });
    }, [orgId]);

    useEffect(() => {
        if (orgId === undefined) return;
        const controller = new AbortController();
        void refresh(controller.signal);
        const whenVisible = () => {
            if (!document.hidden) void refresh(controller.signal);
        };
        window.addEventListener("focus", whenVisible);
        document.addEventListener("visibilitychange", whenVisible);
        return () => {
            controller.abort();
            window.removeEventListener("focus", whenVisible);
            document.removeEventListener("visibilitychange", whenVisible);
        };
    }, [orgId, refresh]);

    const permissions = read !== null && read.orgId === orgId ? read.permissions : null;
    const replace = useCallback((permissions: ExportPermissions) => {
        if (orgId !== undefined && currentOrg.current === orgId) setRead({ orgId, permissions });
    }, [orgId]);
    return { permissions, replace };
}
