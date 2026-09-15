"use client";

import { useLayoutEffect } from "react";

import { synchronizeBrowserAccount } from "@/app/lib/api";

/** Clears previous-account storage before the authenticated shell hydrates persisted record data. */
export default function BrowserAccountBridge({ userId }: { userId: number }) {
    useLayoutEffect(() => synchronizeBrowserAccount(userId), [userId]);
    return null;
}
