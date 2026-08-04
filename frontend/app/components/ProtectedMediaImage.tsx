"use client";

import { createElement, useState, type ComponentProps, type ReactNode, type SyntheticEvent } from "react";

import {
    useProtectedMediaSource,
    useProtectedMediaVisibility,
} from "@/app/hooks/useProtectedMedia";

/** Renders a protected or public image and swaps atomically to the supplied application fallback. */
export default function ProtectedMediaImage({
    src,
    alt,
    fallback = null,
    onError,
    ...props
}: Omit<ComponentProps<"img">, "src" | "alt"> & {
    src: string | null | undefined;
    alt: string;
    fallback?: ReactNode;
}) {
    const { visibilityRef, loadProtectedMedia } = useProtectedMediaVisibility();
    const { resolvedSource, reject } = useProtectedMediaSource(src, loadProtectedMedia);
    const [failedSource, setFailedSource] = useState<string | null>(null);
    const visibleSource = resolvedSource && failedSource !== resolvedSource ? resolvedSource : null;

    const handleError = (event: SyntheticEvent<HTMLImageElement>) => {
        if (!visibleSource) return;
        reject(visibleSource);
        setFailedSource(visibleSource);
        onError?.(event);
    };

    return (
        <span ref={visibilityRef} className="relative grid size-full place-items-center">
            {visibleSource
                ? createElement("img", { ...props, src: visibleSource, alt, onError: handleError })
                : fallback}
        </span>
    );
}
