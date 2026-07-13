"use client";

import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";
import { MenuIcon, PanelLeftCloseIcon, PanelLeftOpenIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import GlobalSearch from "@/app/components/GlobalSearch";
import NavBreadcrumb from "@/app/components/NavBreadcrumb";
import { useTranslations } from "next-intl";

export default function ContentShell({
    sidebar,
    children,
}: {
    sidebar: React.ReactNode;
    children: React.ReactNode;
}) {
    const [open, setOpen] = useState(true);
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

    // Lock the document scroll while the app shell is mounted so the window itself
    // never scrolls (only the <main> content area does). A window scrollbar would drag
    // the whole shell, sidebar included. Restored on unmount so marketing/auth pages
    // outside the shell scroll normally.
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
                className={`fixed inset-y-0 left-0 z-50 transition-[margin,translate] duration-300 ease-out motion-reduce:transition-none md:static md:z-auto md:translate-x-0 ${
                    mobileOpen ? "translate-x-0" : "-translate-x-full"
                } ${open ? "md:ml-0" : "md:-ml-68"}`}
            >
                {sidebar}
            </div>

            <div className="flex min-w-0 flex-1 flex-col">
                <div data-app-toolbar className="relative flex w-full shrink-0 items-center gap-3 p-6 md:justify-center">
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
                    <div className="absolute left-6 hidden max-w-[calc(50%-20rem)] items-center gap-2 md:flex">
                        <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            onClick={() => setOpen((o) => !o)}
                            aria-label={open ? t("hideSidebar") : t("showSidebar")}
                            aria-expanded={open}
                            className="shrink-0"
                        >
                            {open ? (
                                <PanelLeftCloseIcon className="size-5 text-muted-foreground" />
                            ) : (
                                <PanelLeftOpenIcon className="size-5 text-muted-foreground" />
                            )}
                        </Button>
                        <NavBreadcrumb />
                    </div>

                    <div className="w-full max-w-xl min-w-0">
                        <GlobalSearch />
                    </div>
                </div>

                <main data-app-main className="flex-1 overflow-x-hidden overflow-y-auto p-6">
                    {children}
                </main>
            </div>
        </div>
    );
}
