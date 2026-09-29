"use client";

import { useState, useSyncExternalStore, type ReactNode } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import styles from "./landing.module.css";

const subscribe = () => () => {};
const clientSnapshot = () => true;
const serverSnapshot = () => false;

type RecordTab = { id: string; label: string; icon: ReactNode; content: ReactNode };

/** Enhances server-rendered explanations with shared, keyboard-operable tabs. */
export function ConnectedRecordTabs({ label, initialValue, items }: { label: string; initialValue: string; items: RecordTab[] }) {
    const hydrated = useSyncExternalStore(subscribe, clientSnapshot, serverSnapshot);
    const [motion, setMotion] = useState<"still" | "pointer">("still");

    return (
        <Tabs
            defaultValue={initialValue}
            className="mt-8 gap-0"
            data-connected-records
            data-record-motion={motion}
            onPointerDownCapture={() => setMotion("pointer")}
            onKeyDownCapture={() => setMotion("still")}
        >
            <TabsList aria-label={label} variant="line" className={styles.recordTabList} style={hydrated ? undefined : { display: "none" }}>
                {items.map(({ id, label: itemLabel, icon }) => <TabsTrigger key={id} value={id} className={styles.recordTab}>{icon}{itemLabel}</TabsTrigger>)}
            </TabsList>
            {items.map(({ id, content }) => <TabsContent key={id} value={id} className={styles.recordPanel}>{content}</TabsContent>)}
            <noscript>
                {items.filter(({ id }) => id !== initialValue).map(({ id, content }) => <div key={id} className={styles.recordPanel}>{content}</div>)}
            </noscript>
        </Tabs>
    );
}
