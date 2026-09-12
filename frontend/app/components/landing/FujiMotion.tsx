"use client";

import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { PauseIcon, PlayIcon } from "@heroicons/react/24/outline";
import { IconButton } from "@/components/ui/icon-button";
import styles from "./fuji.module.css";

const MOTION_QUERY = "(prefers-reduced-motion: reduce)";

function subscribeMotion(onChange: () => void) {
    const preference = window.matchMedia(MOTION_QUERY);
    preference.addEventListener("change", onChange);
    return () => preference.removeEventListener("change", onChange);
}

function subscribeVisibility(onChange: () => void) {
    document.addEventListener("visibilitychange", onChange);
    return () => document.removeEventListener("visibilitychange", onChange);
}

function motionSnapshot() { return window.matchMedia(MOTION_QUERY).matches; }
function visibilitySnapshot() { return !document.hidden; }
function serverMotionSnapshot() { return true; }
function serverVisibilitySnapshot() { return false; }

/** Keeps decorative clouds still until visible, with pause and reduced-motion controls. */
export function FujiMotion({ children, pauseLabel, resumeLabel }: { children: ReactNode; pauseLabel: string; resumeLabel: string }) {
    const scene = useRef<HTMLDivElement>(null);
    const reduceMotion = useSyncExternalStore(subscribeMotion, motionSnapshot, serverMotionSnapshot);
    const pageVisible = useSyncExternalStore(subscribeVisibility, visibilitySnapshot, serverVisibilitySnapshot);
    const [inViewport, setInViewport] = useState(false);
    const [userPaused, setUserPaused] = useState(false);
    const running = !reduceMotion && pageVisible && inViewport && !userPaused;

    useEffect(() => {
        if (!scene.current || typeof IntersectionObserver === "undefined") return;
        const observer = new IntersectionObserver((entries) => {
            setInViewport(entries.some((entry) => entry.isIntersecting));
        });
        observer.observe(scene.current);
        return () => observer.disconnect();
    }, []);

    useEffect(() => {
        const element = scene.current;
        const section = element?.closest("section");
        if (!element || !section) return;
        const layers = element.querySelectorAll<SVGGElement>("g[data-fuji-depth]");
        if (reduceMotion) {
            layers.forEach((layer) => layer.style.removeProperty("transform"));
            return;
        }
        if (!running) return;

        let frame: number | undefined;
        const updateParallax = () => {
            frame = undefined;
            const distance = Math.max(0, -section.getBoundingClientRect().top);
            layers.forEach((layer) => {
                const depth = Number(layer.dataset.fujiDepth);
                if (Number.isFinite(depth)) layer.style.transform = `translate3d(0, ${distance * depth}px, 0)`;
            });
        };
        const onScroll = () => {
            if (frame === undefined) frame = window.requestAnimationFrame(updateParallax);
        };
        updateParallax();
        window.addEventListener("scroll", onScroll, { passive: true });
        return () => {
            window.removeEventListener("scroll", onScroll);
            if (frame !== undefined) window.cancelAnimationFrame(frame);
        };
    }, [reduceMotion, running]);

    return (
        <div ref={scene} className={styles.backdrop} data-fuji-scene data-fuji-motion={reduceMotion ? "still" : running ? "running" : "paused"}>
            {children}
            <div className={styles.motionControl} hidden={reduceMotion}>
                <IconButton variant="ghost" size="icon-toolbar" className="min-h-11 min-w-11" label={userPaused ? resumeLabel : pauseLabel} onClick={() => setUserPaused((paused) => !paused)}>
                    {userPaused ? <PlayIcon aria-hidden="true" /> : <PauseIcon aria-hidden="true" />}
                </IconButton>
            </div>
        </div>
    );
}
