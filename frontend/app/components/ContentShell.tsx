"use client";

import { useState } from "react";
import { PanelLeftCloseIcon, PanelLeftOpenIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import SearchBar from "@/app/components/SearchBar";

export default function ContentShell({
    sidebar,
    children,
}: {
    sidebar: React.ReactNode;
    children: React.ReactNode;
}) {
    const [open, setOpen] = useState(true);

    return (
        <div className="flex h-screen">
            <div className={open ? "" : "hidden"}>{sidebar}</div>
            <div className="flex-1 flex flex-col">
                <div className="relative flex items-center justify-center p-6 w-full">
                    <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        onClick={() => setOpen((o) => !o)}
                        aria-label={open ? "Hide sidebar" : "Show sidebar"}
                        aria-expanded={open}
                        className="absolute left-6 "
                    >
                        {open ? (
                            <PanelLeftCloseIcon className="size-5 text-neutral-500" />
                        ) : (
                            <PanelLeftOpenIcon className="size-5 text-neutral-500" />
                        )}
                    </Button>
                    <div className="w-full max-w-xl">
                        <SearchBar />
                    </div>
                </div>

                <main className="flex-1 p-6 overflow-y-auto">{children}</main>
            </div>
        </div>
    );
}