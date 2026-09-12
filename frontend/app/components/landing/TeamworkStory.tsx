"use client";

import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type CSSProperties, type ReactNode } from "react";
import styles from "./teamwork.module.css";

const STORY_QUERY = "(min-width: 48rem) and (min-height: 40rem) and (prefers-reduced-motion: no-preference)";
const SCROLL_KEYS = new Set(["ArrowUp", "ArrowDown", "PageUp", "PageDown", "Home", "End", " "]);
const serverSnapshot = () => false;
function storySnapshot() { return typeof IntersectionObserver !== "undefined" && window.matchMedia(STORY_QUERY).matches; }
function subscribeLayout(listener: () => void) {
    const media = window.matchMedia(STORY_QUERY);
    media.addEventListener("change", listener);
    return () => media.removeEventListener("change", listener);
}

type StoryStep = { id: string; content: ReactNode; visual: ReactNode };

/** Pins a complete chapter while natural scroll progress selects its scene; fallback content stays inline. */
export function TeamworkStory({ steps, header, caption, scrollLabel }: { steps: StoryStep[]; header: ReactNode; caption: string; scrollLabel: string }) {
    const root = useRef<HTMLDivElement>(null);
    const eligible = useSyncExternalStore(subscribeLayout, storySnapshot, serverSnapshot);
    const [fits, setFits] = useState(false);
    const enhanced = eligible && fits;
    const [active, setActive] = useState(0);
    const [motion, setMotion] = useState(false);

    useLayoutEffect(() => {
        const element = root.current;
        if (!eligible || !element) return;
        const chapter = element.querySelector<HTMLElement>("[data-team-frame]");
        const unit = element.querySelector<HTMLElement>("[data-team-size]");
        const heading = element.querySelector<HTMLElement>(`.${styles.header}`);
        const stage = element.querySelector<HTMLElement>(`.${styles.stage}`);
        const passages = element.querySelector<HTMLElement>(`.${styles.passages}`);
        const scenes = element.querySelector<HTMLElement>(`.${styles.scenes}`);
        const footer = element.querySelector<HTMLElement>(`.${styles.footer}`);
        if (!chapter || !unit || !heading || !stage || !passages || !scenes || !footer) return;
        let frame: number | undefined;
        let disposed = false;
        const measure = () => {
            frame = undefined;
            element.setAttribute("data-fit-check", "");
            try {
                const frameStyle = getComputedStyle(chapter);
                const required = heading.offsetHeight + Math.max(passages.offsetHeight, scenes.offsetHeight)
                    + footer.offsetHeight + parseFloat(getComputedStyle(stage).marginTop)
                    + parseFloat(frameStyle.paddingTop) + parseFloat(frameStyle.paddingBottom);
                setFits(required <= chapter.clientHeight);
            } finally {
                element.removeAttribute("data-fit-check");
            }
        };
        const schedule = () => {
            if (!disposed && frame === undefined) frame = window.requestAnimationFrame(measure);
        };
        measure();
        let width = element.getBoundingClientRect().width;
        let unitWidth = unit.offsetWidth;
        // Enhancement changes height itself; only width and text-size changes should remeasure it.
        const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver((entries) => {
            for (const entry of entries) {
                if (entry.target === element && entry.contentRect.width !== width) {
                    width = entry.contentRect.width;
                    schedule();
                } else if (entry.target === unit && entry.contentRect.width !== unitWidth) {
                    unitWidth = entry.contentRect.width;
                    schedule();
                }
            }
        });
        observer?.observe(element);
        observer?.observe(unit);
        window.addEventListener("resize", schedule);
        document.fonts?.addEventListener("loadingdone", schedule);
        void document.fonts?.ready.then(schedule);
        return () => {
            disposed = true;
            observer?.disconnect();
            if (frame !== undefined) window.cancelAnimationFrame(frame);
            window.removeEventListener("resize", schedule);
            document.fonts?.removeEventListener("loadingdone", schedule);
        };
    }, [eligible, steps, header, caption, scrollLabel]);

    useEffect(() => {
        const element = root.current;
        if (!enhanced || !element) return;
        const chapter = element.querySelector<HTMLElement>("[data-team-frame]");
        if (!chapter) return;
        let visible = false;
        let frame: number | undefined;
        const update = () => {
            frame = undefined;
            const track = element.getBoundingClientRect();
            const pinned = chapter.getBoundingClientRect();
            const progress = Math.max(0, Math.min(1, (pinned.top - track.top) / Math.max(1, track.height - pinned.height)));
            setActive(Math.min(steps.length - 1, Math.floor(progress * steps.length)));
        };
        const schedule = () => {
            if (visible && frame === undefined) frame = window.requestAnimationFrame(update);
        };
        const observer = new IntersectionObserver((entries) => {
            visible = entries.some((entry) => entry.isIntersecting);
            if (visible) {
                window.addEventListener("scroll", schedule, { passive: true });
                schedule();
            } else {
                window.removeEventListener("scroll", schedule);
                if (frame !== undefined) window.cancelAnimationFrame(frame);
                frame = undefined;
            }
        });
        const onKeyboard = (event: KeyboardEvent) => { if (SCROLL_KEYS.has(event.key)) setMotion(false); };
        const onPointer = () => setMotion(true);
        observer.observe(element);
        window.addEventListener("resize", schedule);
        window.addEventListener("keydown", onKeyboard);
        window.addEventListener("wheel", onPointer, { passive: true });
        window.addEventListener("pointerdown", onPointer, { passive: true });
        return () => {
            observer.disconnect();
            if (frame !== undefined) window.cancelAnimationFrame(frame);
            window.removeEventListener("scroll", schedule);
            window.removeEventListener("resize", schedule);
            window.removeEventListener("keydown", onKeyboard);
            window.removeEventListener("wheel", onPointer);
            window.removeEventListener("pointerdown", onPointer);
        };
    }, [enhanced, steps.length]);

    return (
        <div ref={root} className={styles.story} data-team-story data-enhanced={enhanced} data-team-motion={motion} style={{ "--team-scenes": steps.length } as CSSProperties}>
            <span className={styles.sizeProbe} data-team-size aria-hidden="true" />
            <div className={styles.frame} data-team-frame>
                <div className={styles.header}>{header}</div>
                <div className={styles.stage}>
                    <ol className={styles.passages}>
                        {steps.map(({ id, content, visual }, index) => <li key={id} className={styles.passage} data-team-step={id} data-active={index === active}>
                            <div className={styles.copy}>{content}</div>
                            <div className={styles.inlineVisual} aria-hidden="true">{visual}<p className={styles.caption}>{caption}</p></div>
                        </li>)}
                    </ol>
                    <div className={styles.visualColumn} aria-hidden="true">
                        <div className={styles.scenes}>
                            {steps.map(({ id, visual }, index) => <div key={id} className={styles.scene} data-team-scene={id} data-active={index === active}>{visual}</div>)}
                        </div>
                    </div>
                </div>
                <div className={styles.footer} aria-hidden="true">
                    <div className={styles.progress}>{steps.map(({ id }, index) => <span key={id} data-active={index === active} />)}</div>
                    <p className={styles.caption}>{caption}</p>
                    <p className={styles.scrollLabel}>{scrollLabel}</p>
                </div>
            </div>
        </div>
    );
}
