"use client";

import { Suspense, useEffect, useState, useSyncExternalStore } from "react";
import { usePathname } from "next/navigation";
import { MenuIcon, PanelLeftCloseIcon, PanelLeftOpenIcon } from "lucide-react";
import { motion, useReducedMotion } from "motion/react";
import { Button } from "@/components/ui/button";
import GlobalSearch from "@/app/components/GlobalSearch";
import NavBreadcrumb from "@/app/components/NavBreadcrumb";
import MobileBottomBar from "@/app/components/MobileBottomBar";
import { useAskConnex, useAskConnexMount } from "@/app/components/ask-connex/AskConnexProvider";
import { useSidebarMode } from "@/app/hooks/useSidebarMode";
import { askConnexWidthLength } from "@/app/lib/askConnexSurface";
import { instant, springSmooth } from "@/app/lib/motion";
import { useTranslations } from "next-intl";

const ASK_CONNEX_PUSH_QUERY = "(min-width: 1024px)";

function subscribeAskConnexPush(onChange: () => void): () => void {
    const mediaQuery = window.matchMedia(ASK_CONNEX_PUSH_QUERY);
    mediaQuery.addEventListener("change", onChange);
    return () => mediaQuery.removeEventListener("change", onChange);
}

function getAskConnexPushSnapshot(): boolean {
    return window.matchMedia(ASK_CONNEX_PUSH_QUERY).matches;
}

/**
 * The authenticated app frame: sidebar, toolbar, page, and the column the Ask Connex panel opens
 * into beside the page.
 *
 * That column is the one place this shell animates a layout property, and it is unavoidable: the
 * page genuinely becomes narrower when the panel opens, and no transform can reflow a page. The cost
 * is confined to that moment. A member resizing the panel is adopted instantly rather than springing
 * the whole page through another set of frames — `instantWidth` says so — and the panel itself only
 * translates, so neither surface relayouts its own subtree per frame while it moves.
 */
export default function ContentShell({
    sidebar,
    children,
}: {
    sidebar: React.ReactNode;
    children: React.ReactNode;
}) {
    const { mode, toggle } = useSidebarMode();
    const askConnex = useAskConnex();
    const { registerDesktopRoot } = useAskConnexMount();
    const pushesAskConnex = useSyncExternalStore(
        subscribeAskConnexPush,
        getAskConnexPushSnapshot,
        () => false,
    );
    const reduceMotion = useReducedMotion() ?? false;
    const [mobileOpen, setMobileOpen] = useState(false);
    const pathname = usePathname();
    const t = useTranslations("CommonContentShell");

    const [prevPathname, setPrevPathname] = useState(pathname);
    if (pathname !== prevPathname) {
        setPrevPathname(pathname);
        setMobileOpen(false);
    }

    useEffect(() => {
        const mql = window.matchMedia("(min-width: 768px)");
        const onChange = (e: MediaQueryListEvent) => {
            if (e.matches) setMobileOpen(false);
        };
        mql.addEventListener("change", onChange);
        return () => mql.removeEventListener("change", onChange);
    }, []);

    useEffect(() => {
        const html = document.documentElement;
        const previous = html.style.overflow;
        html.style.overflow = "hidden";
        return () => {
            html.style.overflow = previous;
        };
    }, []);

    useEffect(() => {
        if (!mobileOpen) return;
        const onKeyDown = (e: KeyboardEvent) => {
            if (e.key === "Escape") setMobileOpen(false);
        };
        document.addEventListener("keydown", onKeyDown);
        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        return () => {
            document.removeEventListener("keydown", onKeyDown);
            document.body.style.overflow = previousOverflow;
        };
    }, [mobileOpen]);

    return (
        <div data-app-shell className="flex h-dvh overflow-hidden">
            <div
                aria-hidden
                onClick={() => setMobileOpen(false)}
                className={`fixed inset-0 z-40 bg-black/50 backdrop-blur-[1px] transition-opacity duration-300 motion-reduce:transition-none md:hidden ${
                    mobileOpen ? "opacity-100" : "pointer-events-none opacity-0"
                }`}
            />

            <div
                id="app-sidebar"
                className={`fixed inset-y-0 left-0 z-50 transition-transform duration-300 ease-out motion-reduce:transition-none md:static md:z-auto md:ml-0 md:translate-x-0 ${
                    mobileOpen ? "translate-x-0" : "-translate-x-full"
                }`}
            >
                {sidebar}
            </div>

            <motion.div
                className="grid min-h-0 min-w-0 flex-1 grid-cols-[minmax(0,1fr)_0rem] grid-rows-[minmax(0,1fr)]"
                initial={false}
                animate={{
                    gridTemplateColumns: askConnex.open && !askConnex.workspace && pushesAskConnex
                        ? `minmax(0, 1fr) ${askConnexWidthLength(askConnex.width)}`
                        : "minmax(0, 1fr) 0rem",
                }}
                transition={askConnex.instantOpen || askConnex.instantWidth || reduceMotion
                    ? instant
                    : springSmooth}
            >
                <div className="flex min-w-0 flex-col">
                    {!askConnex.workspace ? (
                        <>
                            <div
                                data-app-toolbar
                                className="relative flex w-full shrink-0 items-center gap-3 p-6 md:grid md:grid-cols-[minmax(0,1fr)_minmax(0,36rem)_minmax(0,1fr)] md:gap-0"
                            >
                                <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon"
                                    onClick={() => setMobileOpen((o) => !o)}
                                    aria-label={t("showSidebar")}
                                    aria-expanded={mobileOpen}
                                    aria-controls="app-sidebar"
                                    className="shrink-0 md:hidden"
                                >
                                    <MenuIcon className="size-5 text-muted-foreground" />
                                </Button>
                                <div className="hidden min-w-0 items-center gap-2 md:flex">
                                    <Button
                                        type="button"
                                        variant="ghost"
                                        size="icon"
                                        onClick={toggle}
                                        aria-label={mode === "expanded" ? t("collapseSidebar") : t("expandSidebar")}
                                        aria-expanded={mode === "expanded"}
                                        className="shrink-0"
                                    >
                                        {mode === "expanded" ? (
                                            <PanelLeftCloseIcon className="size-5 text-muted-foreground" />
                                        ) : (
                                            <PanelLeftOpenIcon className="size-5 text-muted-foreground" />
                                        )}
                                    </Button>
                                    <div className="hidden min-w-0 xl:block">
                                        <Suspense fallback={null}>
                                            <NavBreadcrumb />
                                        </Suspense>
                                    </div>
                                </div>

                                <div className="w-full max-w-xl min-w-0 md:col-start-2 md:row-start-1">
                                    <GlobalSearch />
                                </div>
                            </div>

                            <Suspense fallback={null}>
                                <NavBreadcrumb mode="mobile" />
                            </Suspense>
                        </>
                    ) : null}

                    <main
                        data-app-main
                        className={askConnex.workspace
                            ? "min-h-0 flex-1 overflow-hidden"
                            : "flex-1 overflow-x-hidden overflow-y-auto p-6 pb-[calc(env(safe-area-inset-bottom)+5.5rem)] md:pb-6"}
                    >
                        {children}
                    </main>
                </div>

                <div
                    ref={registerDesktopRoot}
                    className="relative z-30 hidden min-h-0 overflow-visible md:block"
                />
            </motion.div>

            {!mobileOpen && !askConnex.workspace ? (
                <MobileBottomBar onOpenMore={() => setMobileOpen(true)} />
            ) : null}
        </div>
    );
}
