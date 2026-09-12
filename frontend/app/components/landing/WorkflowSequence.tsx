"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { durationExpressiveMs, durationStandardMs } from "@/app/lib/motion";
import styles from "./landing.module.css";

/** Illustrates the trigger-to-action order once; the diagram stays readable in every state. */
export function WorkflowSequence({ children }: { children: ReactNode }) {
    const element = useRef<HTMLDivElement>(null);
    const [active, setActive] = useState(false);

    useEffect(() => {
        if (!element.current || typeof IntersectionObserver === "undefined") return;
        const preference = window.matchMedia("(prefers-reduced-motion: reduce)");
        let played = false;
        let timer: ReturnType<typeof setTimeout> | undefined;
        const finish = () => { clearTimeout(timer); setActive(false); };
        const onVisibility = () => { if (document.hidden) finish(); };
        const observer = new IntersectionObserver((entries) => {
            for (const entry of entries) {
                if (!played && entry.intersectionRatio >= 0.6 && !preference.matches && !document.hidden) {
                    played = true;
                    setActive(true);
                    timer = setTimeout(finish, durationExpressiveMs * 2 + durationStandardMs);
                } else if (!entry.isIntersecting) finish();
            }
        }, { threshold: 0.6 });
        observer.observe(element.current);
        preference.addEventListener("change", finish);
        document.addEventListener("visibilitychange", onVisibility);
        return () => {
            clearTimeout(timer);
            observer.disconnect();
            preference.removeEventListener("change", finish);
            document.removeEventListener("visibilitychange", onVisibility);
        };
    }, []);

    return <div ref={element} className={styles.workflowSequence} data-workflow-active={active}>{children}</div>;
}
