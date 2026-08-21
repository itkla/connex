"use client";

import { useState } from "react";
import { ImageOff } from "lucide-react";

import { normalizeNoteImageSource } from "./editor/noteImageSource";

/** Renders a validated note image with an accessible browser-load fallback. */
export default function NoteMarkdownImage({ source, alt }: { source: string; alt: string }) {
    const normalizedSource = normalizeNoteImageSource(source);
    const [failed, setFailed] = useState(false);

    if (!normalizedSource || failed) {
        return (
            <span className="note-image-frame">
                <span className="note-image-fallback" role="img" aria-label={alt}>
                    <ImageOff className="size-5" aria-hidden="true" />
                    <span>{alt}</span>
                </span>
            </span>
        );
    }

    return (
        <span className="note-image-frame">
            <img
                src={normalizedSource}
                alt={alt}
                loading="lazy"
                decoding="async"
                referrerPolicy="no-referrer"
                onError={() => setFailed(true)}
            />
            <span className="note-image-caption">{alt}</span>
        </span>
    );
}
