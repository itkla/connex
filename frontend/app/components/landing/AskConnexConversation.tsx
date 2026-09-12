"use client";

import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { ArrowPathIcon, ArrowUpIcon, CheckIcon, DocumentTextIcon, ForwardIcon } from "@heroicons/react/24/outline";
import { Button } from "@/components/ui/button";
import { durationExpressiveMs, durationMicroMs, durationStandardMs } from "@/app/lib/motion";
import styles from "./landing.module.css";

const MOTION_QUERY = "(prefers-reduced-motion: reduce)";
const TYPING_MS = durationExpressiveMs * 4;
const THINKING_MS = durationExpressiveMs * 2;
const RESPONSE_MS = durationStandardMs * 3;

function subscribeMotionPreference(onChange: () => void) {
    const preference = window.matchMedia(MOTION_QUERY);
    preference.addEventListener("change", onChange);
    return () => preference.removeEventListener("change", onChange);
}

function reducedMotionSnapshot() { return window.matchMedia(MOTION_QUERY).matches; }
function serverMotionSnapshot() { return true; }

type Phase = "complete" | "typing" | "thinking" | "responding";

type ConversationProps = {
    title: string;
    prompt: string;
    userLabel: string;
    assistantLabel: string;
    exampleLabel: string;
    thinkingLabel: string;
    replayLabel: string;
    skipLabel: string;
    children: ReactNode;
};

/** Plays one local conversation while keeping the complete server-rendered answer available. */
export function AskConnexConversation({ title, prompt, userLabel, assistantLabel, exampleLabel, thinkingLabel, replayLabel, skipLabel, children }: ConversationProps) {
    const reduceMotion = useSyncExternalStore(subscribeMotionPreference, reducedMotionSnapshot, serverMotionSnapshot);
    const [phase, setPhase] = useState<Phase>("complete");
    const [typedPrompt, setTypedPrompt] = useState("");
    const conversation = useRef<HTMLDivElement>(null);
    const arrival = useRef<HTMLDivElement>(null);
    const played = useRef(false);
    const timeouts = useRef<ReturnType<typeof setTimeout>[]>([]);
    const typing = useRef<ReturnType<typeof setInterval> | null>(null);

    const cancelTimers = useCallback(() => {
        timeouts.current.forEach(clearTimeout);
        timeouts.current = [];
        if (typing.current !== null) clearInterval(typing.current);
        typing.current = null;
    }, []);

    const finish = useCallback(() => {
        cancelTimers();
        setPhase("complete");
    }, [cancelTimers]);

    const play = useCallback(() => {
        if (window.matchMedia(MOTION_QUERY).matches) return;
        cancelTimers();
        played.current = true;
        setTypedPrompt("");
        setPhase("typing");
        const characters = Array.from(prompt);
        const started = Date.now();
        typing.current = setInterval(() => {
            const count = Math.min(characters.length, Math.ceil(characters.length * (Date.now() - started) / TYPING_MS));
            setTypedPrompt(characters.slice(0, count).join(""));
        }, durationMicroMs / 3);
        timeouts.current = [
            setTimeout(() => {
                if (typing.current !== null) clearInterval(typing.current);
                typing.current = null;
                setPhase("thinking");
            }, TYPING_MS),
            setTimeout(() => setPhase("responding"), TYPING_MS + THINKING_MS),
            setTimeout(finish, TYPING_MS + THINKING_MS + RESPONSE_MS),
        ];
    }, [cancelTimers, finish, prompt]);

    useEffect(() => {
        const preference = window.matchMedia(MOTION_QUERY);
        const onPreferenceChange = () => finish();
        const onVisibilityChange = () => { if (document.hidden) finish(); };
        preference.addEventListener("change", onPreferenceChange);
        document.addEventListener("visibilitychange", onVisibilityChange);
        const observer = typeof IntersectionObserver === "undefined" ? null : new IntersectionObserver((entries) => {
            for (const entry of entries) {
                if (entry.isIntersecting && entry.intersectionRatio >= 0.6 && !played.current && !document.hidden && !conversation.current?.contains(document.activeElement)) play();
                else if (!entry.isIntersecting && played.current) finish();
            }
        }, { threshold: 0.6 });
        if (arrival.current) observer?.observe(arrival.current);
        return () => {
            observer?.disconnect();
            preference.removeEventListener("change", onPreferenceChange);
            document.removeEventListener("visibilitychange", onVisibilityChange);
            cancelTimers();
        };
    }, [cancelTimers, finish, play]);

    const visiblePhase = reduceMotion ? "complete" : phase;
    const playing = visiblePhase !== "complete";

    return (
        <div ref={conversation} className={`${styles.askConversation} rounded-2xl border border-border bg-card p-5 sm:p-8`} data-ask-phase={visiblePhase}>
            <div ref={arrival}>
                <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 border-b border-border pb-4">
                    <div><p className="flex items-center gap-2 font-semibold"><DocumentTextIcon aria-hidden="true" className="size-5 text-brand-dark dark:text-brand" />{assistantLabel}</p><p className="mt-1 text-sm text-muted-foreground">{exampleLabel}</p></div>
                    <div hidden={reduceMotion}>
                        <Button variant="ghost" size="inline" className="min-h-11 px-3 text-sm" aria-label={playing ? skipLabel : replayLabel} onClick={playing ? finish : play}>
                            {playing ? <ForwardIcon aria-hidden="true" className="size-4" /> : <ArrowPathIcon aria-hidden="true" className="size-4" />}
                            <span aria-hidden="true" className="grid">
                                <span className={`col-start-1 row-start-1 ${playing ? "invisible" : ""}`}>{replayLabel}</span>
                                <span className={`col-start-1 row-start-1 ${playing ? "" : "invisible"}`}>{skipLabel}</span>
                            </span>
                        </Button>
                    </div>
                </div>
                <div className={`${styles.askPrompt} mt-6 ml-auto max-w-[90%] rounded-2xl rounded-br-sm bg-brand/10 p-4`}>
                    <p className="mb-2 text-sm font-semibold">{userLabel}</p>
                    <p className="relative text-base leading-relaxed">
                        <span className={visiblePhase === "typing" ? "opacity-0" : undefined}>{prompt}</span>
                        {visiblePhase === "typing" && <span aria-hidden="true" className="absolute inset-0">{typedPrompt}<span className="ml-0.5 inline-block h-[1em] w-px translate-y-0.5 bg-brand-dark dark:bg-brand" /></span>}
                    </p>
                    <div aria-hidden="true" className="mt-2 flex justify-end text-brand-dark dark:text-brand">{visiblePhase === "typing" ? <ArrowUpIcon className="size-4" /> : <CheckIcon className="size-4" />}</div>
                </div>
            </div>
            <div className="mt-7">
                <p className="mb-4 text-sm font-semibold">{assistantLabel}</p>
                <div className="relative">
                    {visiblePhase === "thinking" && <p className={`${styles.askThinking} absolute inset-x-0 top-0 flex items-center gap-2 text-sm text-muted-foreground`} aria-hidden="true"><span className="size-2 shrink-0 rounded-full bg-brand" />{thinkingLabel}</p>}
                    <div className={styles.askResponse} inert={playing} aria-hidden={playing || undefined}>
                        <div className={styles.askResponsePart}><p className="mb-3 text-sm text-muted-foreground">{title}</p></div>
                        {children}
                    </div>
                </div>
            </div>
        </div>
    );
}
