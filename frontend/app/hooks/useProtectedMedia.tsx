"use client";

import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useState,
    useSyncExternalStore,
    type RefCallback,
    type ReactNode,
} from "react";

import {
    fetchProtectedMediaResponse,
    subscribeClientRequestIdentityInvalidation,
} from "@/app/lib/api";
import {
    classifyProtectedMediaSource,
    isApplicationApiBoundaryPath,
    ProtectedMediaCache,
} from "@/app/lib/protectedMedia";
import { useWorkspace } from "@/app/hooks/useWorkspace";

const ProtectedMediaContext = createContext<ProtectedMediaCache | null>(null);
const SERVER_ORIGIN = "https://connex.invalid";

/** Provides one bounded protected-image cache for the active authenticated workspace. */
export function ProtectedMediaProvider({
    userId,
    children,
}: {
    userId: number;
    children: ReactNode;
}) {
    const { activeWorkspaceId } = useWorkspace();
    const origin = typeof window === "undefined" ? SERVER_ORIGIN : window.location.origin;
    const cache = useMemo(() => new ProtectedMediaCache({
        identity: `${userId}:${activeWorkspaceId ?? "none"}`,
        origin,
        workspaceId: activeWorkspaceId,
        fetcher: fetchProtectedMediaResponse,
    }), [activeWorkspaceId, origin, userId]);

    useEffect(
        () => subscribeClientRequestIdentityInvalidation(() => cache.invalidate()),
        [cache],
    );
    useEffect(() => () => cache.dispose(), [cache]);

    return (
        <ProtectedMediaContext.Provider value={cache}>
            {children}
        </ProtectedMediaContext.Provider>
    );
}

function directSnapshot(source: string | null | undefined): string | null {
    const origin = typeof window === "undefined" ? SERVER_ORIGIN : window.location.origin;
    const classification = classifyProtectedMediaSource(source, origin);
    if (classification.kind !== "direct") return null;
    if (typeof window === "undefined") {
        try {
            if (isApplicationApiBoundaryPath(new URL(classification.source).pathname)) return null;
        } catch {}
    }
    return classification.source;
}

/** Defers protected-media admission until its rendering surface nears the viewport. */
export function useProtectedMediaVisibility(): {
    visibilityRef: RefCallback<HTMLElement>;
    loadProtectedMedia: boolean;
} {
    const [visibilityNode, setVisibilityNode] = useState<HTMLElement | null>(null);
    const [loadProtectedMedia, setLoadProtectedMedia] = useState(false);
    const visibilityRef = useCallback<RefCallback<HTMLElement>>((node) => {
        setVisibilityNode((current) => current === node ? current : node);
        if (!node) setLoadProtectedMedia(false);
        else if (!("IntersectionObserver" in window)) setLoadProtectedMedia(true);
    }, []);
    useEffect(() => {
        if (!visibilityNode || !("IntersectionObserver" in window)) return;
        const observer = new IntersectionObserver((entries) => {
            const intersects = entries.some((entry) => entry.isIntersecting);
            setLoadProtectedMedia((current) => current === intersects ? current : intersects);
        }, { rootMargin: "256px" });
        observer.observe(visibilityNode);
        return () => observer.disconnect();
    }, [visibilityNode]);
    return { visibilityRef, loadProtectedMedia };
}

/** Resolves a managed image to a cached object URL while leaving safe public sources untouched. */
export function useProtectedMediaSource(
    source: string | null | undefined,
    enabled = true,
): {
    resolvedSource: string | null;
    reject: (expectedSource: string) => void;
} {
    const cache = useContext(ProtectedMediaContext);
    const subscribe = useCallback(
        (listener: () => void) => enabled
            ? cache?.subscribe(source, listener) ?? (() => undefined)
            : () => undefined,
        [cache, enabled, source],
    );
    const getSnapshot = useCallback(
        () => enabled ? cache?.getSnapshot(source) ?? directSnapshot(source) : directSnapshot(source),
        [cache, enabled, source],
    );
    const getServerSnapshot = useCallback(
        () => cache?.getServerSnapshot(source) ?? directSnapshot(source),
        [cache, source],
    );
    const resolvedSource = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
    const reject = useCallback(
        (expectedSource: string) => cache?.reject(source, expectedSource),
        [cache, source],
    );
    return { resolvedSource, reject };
}
