"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import type { DocHeading } from "@/app/lib/docs/headings";

/**
 * Sticky "on this page" rail with an IntersectionObserver scroll-spy. Renders
 * nothing when the article has no headings.
 */
export default function OnThisPage({ headings }: { headings: DocHeading[] }) {
    const t = useTranslations("DocsMeta");
    const [activeId, setActiveId] = useState<string | null>(headings[0]?.id ?? null);

    useEffect(() => {
        if (headings.length === 0) return;
        const elements = headings
            .map((heading) => document.getElementById(heading.id))
            .filter((element): element is HTMLElement => element !== null);
        if (elements.length === 0) return;

        const observer = new IntersectionObserver(
            (entries) => {
                const visible = entries.filter((entry) => entry.isIntersecting);
                if (visible.length === 0) return;
                const topmost = visible.reduce((closest, entry) =>
                    entry.boundingClientRect.top < closest.boundingClientRect.top ? entry : closest,
                );
                setActiveId(topmost.target.id);
            },
            { rootMargin: "-80px 0px -70% 0px", threshold: 0 },
        );

        elements.forEach((element) => observer.observe(element));
        return () => observer.disconnect();
    }, [headings]);

    if (headings.length === 0) return null;

    return (
        <nav aria-label={t("onThisPage")} className="text-sm">
            <p className="mb-3 text-xs font-medium uppercase tracking-[0.12em] text-muted-foreground">
                {t("onThisPage")}
            </p>
            <ul className="border-l border-border">
                {headings.map((heading) => {
                    const active = activeId === heading.id;
                    return (
                        <li key={heading.id}>
                            <a
                                href={`#${heading.id}`}
                                aria-current={active ? "location" : undefined}
                                className={`-ml-px block border-l-2 py-1 transition-colors motion-reduce:transition-none ${
                                    heading.level === 3 ? "pl-7" : "pl-4"
                                } ${
                                    active
                                        ? "border-brand font-medium text-brand-dark"
                                        : "border-transparent text-muted-foreground hover:border-border hover:text-foreground"
                                }`}
                            >
                                {heading.text}
                            </a>
                        </li>
                    );
                })}
            </ul>
        </nav>
    );
}
